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
import java.lang.ref.Cleaner;
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
  private static final System.Logger LOGGER = System.getLogger(JdbcExecutor.class.getName());

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
  public <R, P> List<@Nullable R> fetchList(CompiledQueryPlan<R, P> plan, P parameters) {
    return fetchList(plan, parameters, ExecutionContext.EMPTY);
  }

  /** Executes and materializes every result row using an explicit execution context. */
  public <R, P> List<@Nullable R> fetchList(
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
            List<@Nullable R> results = new ArrayList<>();
            while (resultSet.next()) {
              results.add(plan.rowDecoder().decode(resultSet, rowReadContext));
            }
            return results;
          }
        });
  }

  /** Executes a SQL-limited query and returns its first row without cardinality checking. */
  public <R, P> Optional<R> fetchFirst(
      CompiledQueryPlan<R, P> plan, P parameters, ExecutionContext executionContext) {
    JdbcRow<R> row = fetchSingleRow(plan, parameters, executionContext, false, false);
    return row.present() ? Optional.of(Objects.requireNonNull(row.value())) : Optional.empty();
  }

  /** Executes one nullable result row while preserving the distinction between no row and NULL. */
  public <R, P> JdbcRow<R> fetchNullableOne(
      CompiledQueryPlan<R, P> plan, P parameters, ExecutionContext executionContext) {
    return fetchSingleRow(plan, parameters, executionContext, true, true);
  }

  /** Executes one SQL-limited nullable result row without cardinality checking. */
  public <R, P> JdbcRow<R> fetchNullableFirst(
      CompiledQueryPlan<R, P> plan, P parameters, ExecutionContext executionContext) {
    return fetchSingleRow(plan, parameters, executionContext, true, false);
  }

  /** Validates a user-visible page size against effective statement/executor maxRows. */
  public void validateRequestedRows(int requestedRows, ExecutionContext executionContext) {
    if (requestedRows <= 0) {
      throw new IllegalArgumentException("requestedRows must be positive");
    }
    ExecutionOptions options =
        Objects.requireNonNull(executionContext, "executionContext").executionOptions();
    int configured =
        options.hasMaxRows()
            ? options.maxRows()
            : defaultExecutionOptions.hasMaxRows() ? defaultExecutionOptions.maxRows() : 0;
    if (configured > 0 && requestedRows > configured) {
      throw new IllegalArgumentException(
          "requested page size " + requestedRows + " exceeds effective maxRows " + configured);
    }
  }

  /** Executes a size+1 slice while keeping the extra internal row outside visible maxRows. */
  public <R, P> List<@Nullable R> fetchSliceList(
      CompiledQueryPlan<R, P> plan, P parameters, ExecutionContext executionContext, int pageSize) {
    validateRequestedRows(pageSize, executionContext);
    int internalRows;
    try {
      internalRows = Math.addExact(pageSize, 1);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("pageSize + 1 overflows int", exception);
    }
    return withConnection(
        plan,
        executionContext,
        connection -> executeList(connection, plan, parameters, executionContext, internalRows));
  }

  /** Executes content and count on one acquired connection and returns no partial result. */
  public <R, P, C> JdbcPageResult<R> fetchPage(
      CompiledQueryPlan<R, P> contentPlan,
      P contentParameters,
      CompiledQueryPlan<Long, C> countPlan,
      C countParameters,
      ExecutionContext executionContext) {
    Objects.requireNonNull(contentPlan, "contentPlan");
    Objects.requireNonNull(countPlan, "countPlan");
    Objects.requireNonNull(executionContext, "executionContext");
    return withConnection(
        contentPlan,
        executionContext,
        connection -> {
          List<@Nullable R> items =
              executeList(
                  connection,
                  contentPlan,
                  contentParameters,
                  executionContext,
                  NO_MINIMUM_MAX_ROWS);
          try {
            JdbcRow<Long> count =
                executeSingleRow(
                    connection, countPlan, countParameters, executionContext, false, true);
            if (!count.present() || count.value() == null) {
              throw new SQLException("count query did not return one non-null row");
            }
            return new JdbcPageResult<R>(items, count.value());
          } catch (PhasedSqlFailure failure) {
            throw translate(countPlan, failure.phase(), failure.sqlException());
          } catch (SQLException failure) {
            throw translate(countPlan, JdbcFailureDiagnostics.Phase.EXECUTE, failure);
          }
        });
  }

  /** Opens an explicit cursor that owns its ResultSet, Statement, and acquired connection. */
  public <R, P> JdbcCursor<R> openCursor(
      CompiledQueryPlan<R, P> plan, P parameters, ExecutionContext executionContext) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(executionContext, "executionContext");
    Connection connection;
    try {
      connection = connectionProvider.acquire(executionContext);
    } catch (SQLException failure) {
      throw translate(plan, JdbcFailureDiagnostics.Phase.ACQUIRE, failure);
    }
    PreparedStatement statement = null;
    try {
      statement = prepare(connection, plan, parameters, executionContext, NO_MINIMUM_MAX_ROWS);
      ResultSet resultSet = statement.executeQuery();
      return new DefaultJdbcCursor<>(plan, executionContext, connection, statement, resultSet);
    } catch (PhasedSqlFailure failure) {
      RuntimeException primary = translate(plan, failure.phase(), failure.sqlException());
      closeOpenFailure(plan, connection, statement, executionContext, primary);
      throw primary;
    } catch (SQLException failure) {
      RuntimeException primary = translate(plan, JdbcFailureDiagnostics.Phase.EXECUTE, failure);
      closeOpenFailure(plan, connection, statement, executionContext, primary);
      throw primary;
    } catch (RuntimeException | Error failure) {
      closeOpenFailure(plan, connection, statement, executionContext, failure);
      throw failure;
    }
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
            var result = plan.rowDecoder().decode(resultSet, rowReadContext);
            if (result == null) {
              throw new SQLException("non-null query decoder returned null");
            }
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

  private <R, P> JdbcRow<R> fetchSingleRow(
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      boolean nullable,
      boolean checkMultiple) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(executionContext, "executionContext");
    return withConnection(
        plan,
        executionContext,
        connection ->
            executeSingleRow(
                connection, plan, parameters, executionContext, nullable, checkMultiple));
  }

  private <R, P> List<@Nullable R> executeList(
      Connection connection,
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      int minimumMaxRows)
      throws SQLException {
    try (PreparedStatement statement =
            prepare(connection, plan, parameters, executionContext, minimumMaxRows);
        ResultSet resultSet = statement.executeQuery()) {
      List<@Nullable R> results = new ArrayList<>();
      while (resultSet.next()) {
        results.add(plan.rowDecoder().decode(resultSet, rowReadContext));
      }
      return results;
    }
  }

  private <R, P> JdbcRow<R> executeSingleRow(
      Connection connection,
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      boolean nullable,
      boolean checkMultiple)
      throws SQLException {
    int minimumMaxRows = checkMultiple ? FETCH_ONE_CARDINALITY_ROWS : NO_MINIMUM_MAX_ROWS;
    try (PreparedStatement statement =
            prepare(connection, plan, parameters, executionContext, minimumMaxRows);
        ResultSet resultSet = statement.executeQuery()) {
      if (!resultSet.next()) {
        return JdbcRow.absent();
      }
      JdbcRow<R> row = JdbcRow.present(plan.rowDecoder().decode(resultSet, rowReadContext));
      if (!nullable && row.value() == null) {
        throw new SQLException("non-null query decoder returned null");
      }
      if (checkMultiple && resultSet.next()) {
        throw new NonUniqueResultException(
            "query expected at most one row [sqlFingerprint="
                + QueryExecutionException.fingerprint(plan.sql())
                + "]");
      }
      return row;
    }
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

  private QueryExecutionException translate(
      CompiledQueryPlan<?, ?> plan, JdbcFailureDiagnostics.Phase phase, SQLException failure) {
    return QueryExecutionException.from(
        phase, plan.dialectId(), plan.sql(), failure, classify(failure));
  }

  private void closeOpenFailure(
      CompiledQueryPlan<?, ?> plan,
      Connection connection,
      @Nullable PreparedStatement statement,
      ExecutionContext executionContext,
      Throwable primary) {
    if (statement != null) {
      try {
        statement.close();
      } catch (SQLException failure) {
        primary.addSuppressed(
            translate(plan, JdbcFailureDiagnostics.Phase.STATEMENT_CLOSE, failure));
      } catch (RuntimeException | Error failure) {
        primary.addSuppressed(failure);
      }
    }
    try {
      connectionProvider.release(connection, executionContext);
    } catch (SQLException failure) {
      primary.addSuppressed(translate(plan, JdbcFailureDiagnostics.Phase.RELEASE, failure));
    } catch (RuntimeException | Error failure) {
      primary.addSuppressed(failure);
    }
  }

  private final class DefaultJdbcCursor<R> implements JdbcCursor<R> {

    private final CompiledQueryPlan<R, ?> plan;
    private final CursorResources<R> resources;
    private final Cleaner.Cleanable cleanable;
    private boolean positioned;
    private @Nullable R current;

    private DefaultJdbcCursor(
        CompiledQueryPlan<R, ?> plan,
        ExecutionContext executionContext,
        Connection connection,
        PreparedStatement statement,
        ResultSet resultSet) {
      this.plan = plan;
      this.resources =
          new CursorResources<>(plan, executionContext, connection, statement, resultSet);
      this.cleanable = CursorCleanerHolder.INSTANCE.register(this, resources);
    }

    @Override
    public boolean advance() {
      if (resources.isClosed()) {
        return false;
      }
      positioned = false;
      current = null;
      try {
        if (!resources.resultSet.next()) {
          close();
          return false;
        }
        current = plan.rowDecoder().decode(resources.resultSet, rowReadContext);
        positioned = true;
        return true;
      } catch (SQLException failure) {
        RuntimeException primary = translate(plan, JdbcFailureDiagnostics.Phase.EXECUTE, failure);
        throw runtimeFailure(closeResources(primary));
      } catch (RuntimeException | Error failure) {
        throw runtimeFailure(closeResources(failure));
      }
    }

    @Override
    public @Nullable R current() {
      if (!positioned || resources.isClosed()) {
        throw new IllegalStateException(
            "current() requires a successful advance() and an open cursor");
      }
      return current;
    }

    @Override
    public boolean isClosed() {
      return resources.isClosed();
    }

    @Override
    public void close() {
      if (resources.isClosed()) {
        return;
      }
      Throwable failure = closeResources(null);
      if (failure instanceof Error error) {
        throw error;
      }
      if (failure instanceof RuntimeException runtime) {
        throw runtime;
      }
    }

    private @Nullable Throwable closeResources(@Nullable Throwable primary) {
      positioned = false;
      current = null;
      Throwable result = resources.closeResources(primary);
      cleanable.clean();
      return result;
    }

    private RuntimeException runtimeFailure(@Nullable Throwable failure) {
      if (failure instanceof RuntimeException runtime) {
        return runtime;
      }
      if (failure instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("cursor cleanup did not preserve the primary failure");
    }
  }

  private final class CursorResources<R> implements Runnable {

    private final CompiledQueryPlan<R, ?> plan;
    private final ExecutionContext executionContext;
    private final Connection connection;
    private final PreparedStatement statement;
    private final ResultSet resultSet;
    private boolean closed;

    private CursorResources(
        CompiledQueryPlan<R, ?> plan,
        ExecutionContext executionContext,
        Connection connection,
        PreparedStatement statement,
        ResultSet resultSet) {
      this.plan = plan;
      this.executionContext = executionContext;
      this.connection = connection;
      this.statement = statement;
      this.resultSet = resultSet;
    }

    private synchronized boolean isClosed() {
      return closed;
    }

    private synchronized @Nullable Throwable closeResources(@Nullable Throwable primary) {
      if (closed) {
        return primary;
      }
      closed = true;
      Throwable result = primary;
      result = closeResultSet(result);
      result = closeStatement(result);
      result = releaseConnection(result);
      return result;
    }

    @Override
    public synchronized void run() {
      if (closed) {
        return;
      }
      String fingerprint = QueryExecutionException.fingerprint(plan.sql());
      LOGGER.log(
          System.Logger.Level.WARNING,
          "JDBC cursor was abandoned without close [dialect="
              + plan.dialectId()
              + ", sqlFingerprint="
              + fingerprint
              + "]");
      // JDBC cleanup is intentionally not attempted from the Cleaner thread. Providers backed by
      // external transaction managers may require release on the acquiring thread.
    }

    private @Nullable Throwable closeResultSet(@Nullable Throwable primary) {
      try {
        resultSet.close();
        return primary;
      } catch (SQLException failure) {
        return append(
            primary, translate(plan, JdbcFailureDiagnostics.Phase.RESULT_SET_CLOSE, failure));
      } catch (RuntimeException | Error failure) {
        return append(primary, failure);
      }
    }

    private @Nullable Throwable closeStatement(@Nullable Throwable primary) {
      try {
        statement.close();
        return primary;
      } catch (SQLException failure) {
        return append(
            primary, translate(plan, JdbcFailureDiagnostics.Phase.STATEMENT_CLOSE, failure));
      } catch (RuntimeException | Error failure) {
        return append(primary, failure);
      }
    }

    private @Nullable Throwable releaseConnection(@Nullable Throwable primary) {
      try {
        connectionProvider.release(connection, executionContext);
        return primary;
      } catch (SQLException failure) {
        return append(primary, translate(plan, JdbcFailureDiagnostics.Phase.RELEASE, failure));
      } catch (RuntimeException | Error failure) {
        return append(primary, failure);
      }
    }

    private Throwable append(@Nullable Throwable primary, Throwable next) {
      if (primary == null) {
        return next;
      }
      primary.addSuppressed(next);
      return primary;
    }
  }

  private static final class CursorCleanerHolder {

    private static final Cleaner INSTANCE = Cleaner.create();
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
