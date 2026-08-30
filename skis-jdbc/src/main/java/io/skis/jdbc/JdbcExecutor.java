package io.skis.jdbc;

import io.skis.core.ExecutionContext;
import io.skis.core.ExecutionOptions;
import io.skis.core.NonUniqueResultException;
import io.skis.core.QueryTag;
import io.skis.dialect.ExceptionClassifier;
import io.skis.dialect.SqlExceptionCategory;
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
import org.jspecify.annotations.Nullable;

/** Thread-safe JDBC execution kernel for immutable compiled query and mutation plans. */
public final class JdbcExecutor {

  private static final int NO_MINIMUM_MAX_ROWS = 0;
  private static final int FETCH_ONE_CARDINALITY_ROWS = 2;

  private final ConnectionProvider connectionProvider;
  private final JdbcWriteContext writeContext;
  private final RowReadContext rowReadContext;
  private final ExecutionOptions defaultExecutionOptions;
  private final boolean defaultExecutionOptionsConfigured;
  private final ExceptionClassifier exceptionClassifier;

  /** Creates an executor using the default generated-code contexts. */
  public JdbcExecutor(ConnectionProvider connectionProvider) {
    this(
        connectionProvider,
        JdbcWriteContext.EMPTY,
        RowReadContext.EMPTY,
        ExecutionOptions.NONE,
        ExceptionClassifier.NONE);
  }

  /** Creates an executor with explicit immutable codec contexts. */
  public JdbcExecutor(
      ConnectionProvider connectionProvider,
      JdbcWriteContext writeContext,
      RowReadContext rowReadContext) {
    this(
        connectionProvider,
        writeContext,
        rowReadContext,
        ExecutionOptions.NONE,
        ExceptionClassifier.NONE);
  }

  /** Creates a fully configured executor; all supplied collaborators must be thread-safe. */
  public JdbcExecutor(
      ConnectionProvider connectionProvider,
      JdbcWriteContext writeContext,
      RowReadContext rowReadContext,
      ExecutionOptions defaultExecutionOptions,
      ExceptionClassifier exceptionClassifier) {
    this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    this.writeContext = Objects.requireNonNull(writeContext, "writeContext");
    this.rowReadContext = Objects.requireNonNull(rowReadContext, "rowReadContext");
    this.defaultExecutionOptions =
        Objects.requireNonNull(defaultExecutionOptions, "defaultExecutionOptions");
    this.defaultExecutionOptionsConfigured = !defaultExecutionOptions.isEmpty();
    this.exceptionClassifier = Objects.requireNonNull(exceptionClassifier, "exceptionClassifier");
  }

  /**
   * Rebinds the immutable execution configuration to another connection provider.
   *
   * <p>This is used by transaction sessions so compiled plans, codec contexts, defaults and
   * exception classification remain identical while all work shares one connection.
   */
  public JdbcExecutor withConnectionProvider(ConnectionProvider newConnectionProvider) {
    return new JdbcExecutor(
        Objects.requireNonNull(newConnectionProvider, "newConnectionProvider"),
        writeContext,
        rowReadContext,
        defaultExecutionOptions,
        exceptionClassifier);
  }

  /** Returns an executor whose session defaults overlay this executor's immutable defaults. */
  public JdbcExecutor withDefaultExecutionOptions(ExecutionOptions executionOptions) {
    ExecutionOptions effectiveDefaults =
        defaultExecutionOptions.overriddenBy(
            Objects.requireNonNull(executionOptions, "executionOptions"));
    if (effectiveDefaults == defaultExecutionOptions) {
      return this;
    }
    return new JdbcExecutor(
        connectionProvider, writeContext, rowReadContext, effectiveDefaults, exceptionClassifier);
  }

  ConnectionProvider connectionProvider() {
    return connectionProvider;
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
          try (PreparedStatement statement =
                  prepare(connection, plan, parameters, executionContext, NO_MINIMUM_MAX_ROWS);
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
          try (PreparedStatement statement =
                  prepare(
                      connection, plan, parameters, executionContext, FETCH_ONE_CARDINALITY_ROWS);
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
                plan.operation(), phase, plan.dialectId(), plan.sql(), failure, classify(failure)),
        connection -> {
          try (PreparedStatement statement =
              prepare(
                  connection,
                  plan.sql(),
                  plan.parameterCount(),
                  plan.parameterBinder(),
                  parameters,
                  executionContext,
                  NO_MINIMUM_MAX_ROWS)) {
            return statement.executeUpdate();
          }
        });
  }

  private <R, P> PreparedStatement prepare(
      Connection connection,
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      int minimumMaxRows)
      throws SQLException {
    return prepare(
        connection,
        plan.sql(),
        plan.parameterCount(),
        plan.parameterBinder(),
        parameters,
        executionContext,
        minimumMaxRows);
  }

  private <P> PreparedStatement prepare(
      Connection connection,
      String sql,
      int parameterCount,
      ParameterBinder<P> parameterBinder,
      P parameters,
      ExecutionContext executionContext,
      int minimumMaxRows)
      throws SQLException {
    ExecutionOptions statementOptions =
        executionContext == ExecutionContext.EMPTY
            ? ExecutionOptions.NONE
            : Objects.requireNonNull(
                executionContext.executionOptions(), "executionContext options");
    boolean optionsConfigured =
        defaultExecutionOptionsConfigured
            || (statementOptions != ExecutionOptions.NONE && !statementOptions.isEmpty());
    PreparedStatement statement =
        connection.prepareStatement(optionsConfigured ? taggedSql(sql, statementOptions) : sql);
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
      if (optionsConfigured) {
        try {
          applyExecutionOptions(statement, statementOptions, minimumMaxRows);
        } catch (SQLException failure) {
          throw new PhasedSqlFailure(JdbcFailureDiagnostics.Phase.CONFIGURE, failure);
        }
      }
      return statement;
    } catch (PhasedSqlFailure failure) {
      closeAfterPrepareFailure(statement, failure.sqlException());
      throw failure;
    } catch (SQLException | RuntimeException | Error failure) {
      closeAfterPrepareFailure(statement, failure);
      throw failure;
    }
  }

  private void applyExecutionOptions(
      PreparedStatement statement, ExecutionOptions statementOptions, int minimumMaxRows)
      throws SQLException {
    if (statementOptions.hasStatementTimeout()) {
      statement.setQueryTimeout(statementOptions.queryTimeoutSeconds());
    } else if (defaultExecutionOptions.hasStatementTimeout()) {
      statement.setQueryTimeout(defaultExecutionOptions.queryTimeoutSeconds());
    }

    if (statementOptions.hasFetchSize()) {
      statement.setFetchSize(statementOptions.fetchSize());
    } else if (defaultExecutionOptions.hasFetchSize()) {
      statement.setFetchSize(defaultExecutionOptions.fetchSize());
    }

    if (statementOptions.hasMaxRows()) {
      statement.setMaxRows(adjustMaxRows(statementOptions.maxRows(), minimumMaxRows));
    } else if (defaultExecutionOptions.hasMaxRows()) {
      statement.setMaxRows(adjustMaxRows(defaultExecutionOptions.maxRows(), minimumMaxRows));
    }
  }

  private static int adjustMaxRows(int configuredMaxRows, int minimumMaxRows) {
    if (configuredMaxRows == 0) {
      return 0;
    }
    return Math.max(configuredMaxRows, minimumMaxRows);
  }

  private String taggedSql(String sql, ExecutionOptions statementOptions) {
    QueryTag queryTag = effectiveQueryTag(statementOptions);
    if (queryTag == null) {
      return sql;
    }
    return "/* skis:" + queryTag.value() + " */ " + sql;
  }

  private @Nullable QueryTag effectiveQueryTag(ExecutionOptions statementOptions) {
    if (statementOptions.isQueryTagConfigured()) {
      return statementOptions.queryTag();
    }
    if (defaultExecutionOptions.isQueryTagConfigured()) {
      return defaultExecutionOptions.queryTag();
    }
    return null;
  }

  private static void closeAfterPrepareFailure(PreparedStatement statement, Throwable failure) {
    try {
      statement.close();
    } catch (SQLException | RuntimeException | Error closeFailure) {
      failure.addSuppressed(closeFailure);
    }
  }

  private <R, P> R withConnection(
      CompiledQueryPlan<?, P> plan, ExecutionContext executionContext, SqlWork<R> work) {
    return withConnection(
        executionContext,
        (phase, failure) ->
            QueryExecutionException.from(
                phase, plan.dialectId(), plan.sql(), failure, classify(failure)),
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
    } catch (PhasedSqlFailure failure) {
      RuntimeException translated =
          failureTranslator.translate(failure.phase(), failure.sqlException());
      pendingFailure = translated;
      throw translated;
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

  private SqlExceptionCategory classify(SQLException failure) {
    try {
      SqlExceptionCategory category = exceptionClassifier.classify(failure);
      return Objects.requireNonNull(category, "exception classifier result");
    } catch (RuntimeException | Error classifierFailure) {
      failure.addSuppressed(classifierFailure);
      return SqlExceptionCategory.UNCATEGORIZED;
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

  private static final class PhasedSqlFailure extends RuntimeException {

    private final JdbcFailureDiagnostics.Phase phase;
    private final SQLException sqlException;

    private PhasedSqlFailure(JdbcFailureDiagnostics.Phase phase, SQLException sqlException) {
      super(sqlException);
      this.phase = phase;
      this.sqlException = sqlException;
    }

    private JdbcFailureDiagnostics.Phase phase() {
      return phase;
    }

    private SQLException sqlException() {
      return sqlException;
    }
  }
}
