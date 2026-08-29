package io.skis.jdbc;

import io.skis.core.ExecutionContext;
import io.skis.core.NonUniqueResultException;
import io.skis.mapping.JdbcWriteContext;
import io.skis.mapping.ParameterBinder;
import io.skis.mapping.RowReadContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Thread-safe JDBC execution kernel for immutable compiled query and mutation plans. */
public final class JdbcExecutor {

  private final ConnectionProvider connectionProvider;
  private final JdbcWriteContext writeContext;
  private final RowReadContext rowReadContext;

  /** Creates an executor using the default generated-code contexts. */
  public JdbcExecutor(ConnectionProvider connectionProvider) {
    this(connectionProvider, JdbcWriteContext.EMPTY, RowReadContext.EMPTY);
  }

  /** Creates an executor with explicit immutable codec contexts. */
  public JdbcExecutor(
      ConnectionProvider connectionProvider,
      JdbcWriteContext writeContext,
      RowReadContext rowReadContext) {
    this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    this.writeContext = Objects.requireNonNull(writeContext, "writeContext");
    this.rowReadContext = Objects.requireNonNull(rowReadContext, "rowReadContext");
  }

  /** Executes and materializes every result row. */
  public <R, P> List<R> fetchList(CompiledQueryPlan<R, P> plan, P parameters) {
    return fetchList(plan, parameters, ExecutionContext.EMPTY);
  }

  /** Executes and materializes every result row using an explicit execution context. */
  public <R, P> List<R> fetchList(
      CompiledQueryPlan<R, P> plan, P parameters, ExecutionContext executionContext) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(executionContext, "executionContext");
    return withConnection(
        plan,
        executionContext,
        connection -> {
          try (PreparedStatement statement = prepare(connection, plan, parameters);
              ResultSet resultSet = statement.executeQuery()) {
            List<R> results = new ArrayList<>();
            while (resultSet.next()) {
              results.add(plan.rowDecoder().decode(resultSet, rowReadContext));
            }
            return results;
          }
        });
  }

  /** Executes a query requiring at most one row. */
  public <R, P> Optional<R> fetchOne(CompiledQueryPlan<R, P> plan, P parameters) {
    return fetchOne(plan, parameters, ExecutionContext.EMPTY);
  }

  /** Executes a query requiring at most one row using an explicit execution context. */
  public <R, P> Optional<R> fetchOne(
      CompiledQueryPlan<R, P> plan, P parameters, ExecutionContext executionContext) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(executionContext, "executionContext");
    return withConnection(
        plan,
        executionContext,
        connection -> {
          try (PreparedStatement statement = prepare(connection, plan, parameters);
              ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
              return Optional.empty();
            }
            R result = plan.rowDecoder().decode(resultSet, rowReadContext);
            if (resultSet.next()) {
              throw new NonUniqueResultException(
                  "query expected at most one row [sqlFingerprint="
                      + QueryExecutionException.fingerprint(plan.sql())
                      + "]");
            }
            return Optional.of(result);
          }
        });
  }

  /** Executes a compiled INSERT, UPDATE, or DELETE plan and returns its affected-row count. */
  public <P> int executeUpdate(CompiledMutationPlan<P> plan, P parameters) {
    return executeUpdate(plan, parameters, ExecutionContext.EMPTY);
  }

  /** Executes a compiled mutation plan using an explicit execution context. */
  public <P> int executeUpdate(
      CompiledMutationPlan<P> plan, P parameters, ExecutionContext executionContext) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(executionContext, "executionContext");
    return withConnection(
        executionContext,
        (phase, failure) ->
            JdbcExecutionException.from(
                plan.operation(), phase, plan.dialectId(), plan.sql(), failure),
        connection -> {
          try (PreparedStatement statement =
              prepare(
                  connection,
                  plan.sql(),
                  plan.parameterCount(),
                  plan.parameterBinder(),
                  parameters)) {
            return statement.executeUpdate();
          }
        });
  }

  private <R, P> PreparedStatement prepare(
      Connection connection, CompiledQueryPlan<R, P> plan, P parameters) throws SQLException {
    return prepare(
        connection, plan.sql(), plan.parameterCount(), plan.parameterBinder(), parameters);
  }

  private <P> PreparedStatement prepare(
      Connection connection,
      String sql,
      int parameterCount,
      ParameterBinder<P> parameterBinder,
      P parameters)
      throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    try {
      int nextIndex = parameterBinder.bind(statement, 1, parameters, writeContext);
      int expectedNextIndex = parameterCount + 1;
      if (nextIndex != expectedNextIndex) {
        throw new SQLException(
            "compiled parameter binder returned index "
                + nextIndex
                + " but expected "
                + expectedNextIndex);
      }
      return statement;
    } catch (SQLException | RuntimeException | Error failure) {
      try {
        statement.close();
      } catch (SQLException | RuntimeException | Error closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  private <R, P> R withConnection(
      CompiledQueryPlan<?, P> plan, ExecutionContext executionContext, SqlWork<R> work) {
    return withConnection(
        executionContext,
        (phase, failure) ->
            QueryExecutionException.from(phase, plan.dialectId(), plan.sql(), failure),
        work);
  }

  private <R> R withConnection(
      ExecutionContext executionContext, SqlFailureTranslator failureTranslator, SqlWork<R> work) {
    Connection connection;
    try {
      connection = connectionProvider.acquire(executionContext);
    } catch (SQLException failure) {
      throw failureTranslator.translate(JdbcFailureDiagnostics.Phase.ACQUIRE, failure);
    }

    Throwable pendingFailure = null;
    try {
      return work.execute(connection);
    } catch (SQLException failure) {
      RuntimeException translated =
          failureTranslator.translate(JdbcFailureDiagnostics.Phase.EXECUTE, failure);
      pendingFailure = translated;
      throw translated;
    } catch (RuntimeException | Error failure) {
      pendingFailure = failure;
      throw failure;
    } finally {
      try {
        connectionProvider.release(connection, executionContext);
      } catch (SQLException releaseFailure) {
        if (pendingFailure != null) {
          pendingFailure.addSuppressed(releaseFailure);
        } else {
          throw failureTranslator.translate(JdbcFailureDiagnostics.Phase.RELEASE, releaseFailure);
        }
      } catch (RuntimeException | Error releaseFailure) {
        if (pendingFailure != null) {
          pendingFailure.addSuppressed(releaseFailure);
        } else {
          throw releaseFailure;
        }
      }
    }
  }

  @FunctionalInterface
  private interface SqlWork<R> {
    R execute(Connection connection) throws SQLException;
  }

  @FunctionalInterface
  private interface SqlFailureTranslator {
    RuntimeException translate(JdbcFailureDiagnostics.Phase phase, SQLException failure);
  }
}
