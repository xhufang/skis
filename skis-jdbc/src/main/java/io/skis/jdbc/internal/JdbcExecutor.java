package io.skis.jdbc.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.MutationException;
import io.skis.core.NonUniqueResultException;
import io.skis.core.QueryTag;
import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisErrorCode;
import io.skis.core.SkisOperation;
import io.skis.core.SkisStatementKind;
import io.skis.core.SqlLoggingOptions;
import io.skis.core.internal.OperationObservation;
import io.skis.core.internal.SafeLog;
import io.skis.core.internal.StatementObservation;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.StatementCompletedEvent;
import io.skis.core.spi.StatementPurpose;
import io.skis.dialect.spi.ExceptionClassifier;
import io.skis.dialect.spi.SqlExceptionCategory;
import io.skis.jdbc.JdbcExecutionException;
import io.skis.jdbc.JdbcPhase;
import io.skis.jdbc.QueryExecutionException;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.ParameterBinder;
import io.skis.mapping.spi.RowDecoder;
import io.skis.mapping.spi.RowReadContext;
import java.lang.ref.Cleaner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Thread-safe JDBC execution kernel for immutable compiled query and mutation plans. */
public final class JdbcExecutor {

  private static final int NO_MINIMUM_MAX_ROWS = 0;
  private static final int UNCONFIGURED_QUERY_TIMEOUT = -1;
  private static final int FETCH_ONE_CARDINALITY_ROWS = 2;
  private static final Object CURSOR_NOT_POSITIONED = new Object();
  private static final Logger LOGGER = LoggerFactory.getLogger("io.skis.internal");
  private static final Logger PARAMETERS = LoggerFactory.getLogger("io.skis.sql.parameters");

  private final ConnectionProvider connectionProvider;
  private final JdbcWriteContext writeContext;
  private final RowReadContext rowReadContext;
  private final ExecutionOptions defaultExecutionOptions;
  private final boolean defaultExecutionOptionsConfigured;
  private final ExceptionClassifier exceptionClassifier;
  private final SqlLoggingOptions loggingOptions;

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
    this(
        connectionProvider,
        writeContext,
        rowReadContext,
        defaultExecutionOptions,
        exceptionClassifier,
        SqlLoggingOptions.NONE);
  }

  /** Creates an executor with immutable opt-in logging and synchronous redaction configuration. */
  public JdbcExecutor(
      ConnectionProvider connectionProvider,
      JdbcWriteContext writeContext,
      RowReadContext rowReadContext,
      ExecutionOptions defaultExecutionOptions,
      ExceptionClassifier exceptionClassifier,
      SqlLoggingOptions loggingOptions) {
    this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    this.writeContext = Objects.requireNonNull(writeContext, "writeContext");
    this.rowReadContext = Objects.requireNonNull(rowReadContext, "rowReadContext");
    this.defaultExecutionOptions =
        Objects.requireNonNull(defaultExecutionOptions, "defaultExecutionOptions");
    this.defaultExecutionOptionsConfigured = !defaultExecutionOptions.isEmpty();
    this.exceptionClassifier = Objects.requireNonNull(exceptionClassifier, "exceptionClassifier");
    this.loggingOptions = Objects.requireNonNull(loggingOptions, "loggingOptions");
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
        exceptionClassifier,
        loggingOptions);
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
        connectionProvider,
        writeContext,
        rowReadContext,
        effectiveDefaults,
        exceptionClassifier,
        loggingOptions);
  }

  ConnectionProvider connectionProvider() {
    return connectionProvider;
  }

  /** Executes and materializes every result row. */
  public <R extends @Nullable Object, P> List<R> fetchList(
      CompiledQueryPlan<R, P> plan, P parameters) {
    return fetchList(plan, parameters, ExecutionContext.EMPTY);
  }

  /** Executes and materializes every result row using an explicit execution context. */
  public <R extends @Nullable Object, P> List<R> fetchList(
      CompiledQueryPlan<R, P> plan, P parameters, ExecutionContext executionContext) {
    return fetchList(plan, parameters, executionContext, null, StatementPurpose.PRIMARY);
  }

  /** Executes and observes every materialized result row. */
  public <R extends @Nullable Object, P> List<R> fetchList(
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(executionContext, "executionContext");
    return withConnection(
        plan,
        executionContext,
        connection ->
            executeList(
                connection,
                plan,
                parameters,
                executionContext,
                NO_MINIMUM_MAX_ROWS,
                observation,
                purpose));
  }

  /** Executes a SQL-limited query and returns its first row without cardinality checking. */
  @SuppressWarnings("UnusedReturnValue")
  public <R, P> Optional<R> fetchFirst(
      CompiledQueryPlan<R, P> plan, P parameters, ExecutionContext executionContext) {
    JdbcRow<R> row =
        fetchSingleRow(
            plan, parameters, executionContext, false, false, null, StatementPurpose.PRIMARY);
    return row.present() ? Optional.of(Objects.requireNonNull(row.value())) : Optional.empty();
  }

  /** Executes one nullable result row while preserving the distinction between no row and NULL. */
  public <R extends @Nullable Object, P> JdbcRow<R> fetchNullableOne(
      CompiledQueryPlan<R, P> plan, P parameters, ExecutionContext executionContext) {
    return fetchNullableOne(plan, parameters, executionContext, null, StatementPurpose.PRIMARY);
  }

  /** Executes and observes one nullable result row. */
  public <R extends @Nullable Object, P> JdbcRow<R> fetchNullableOne(
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    return fetchSingleRow(plan, parameters, executionContext, true, true, observation, purpose);
  }

  /** Executes and observes one SQL-limited nullable result row. */
  public <R extends @Nullable Object, P> JdbcRow<R> fetchNullableFirst(
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    return fetchSingleRow(plan, parameters, executionContext, true, false, observation, purpose);
  }

  /** Validates a user-visible page size against effective statement/executor maxRows. */
  public void validateRequestedRows(int requestedRows, ExecutionContext executionContext) {
    if (requestedRows <= 0) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED, "requestedRows must be positive");
    }
    ExecutionOptions options =
        Objects.requireNonNull(executionContext, "executionContext").executionOptions();
    int configured =
        options.hasMaxRows()
            ? options.maxRows()
            : defaultExecutionOptions.hasMaxRows() ? defaultExecutionOptions.maxRows() : 0;
    if (configured > 0 && requestedRows > configured) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED,
          "requested page size exceeds the effective maxRows");
    }
  }

  /** Executes a size+1 slice while keeping the extra internal row outside visible maxRows. */
  public <R extends @Nullable Object, P> List<R> fetchSliceList(
      CompiledQueryPlan<R, P> plan, P parameters, ExecutionContext executionContext, int pageSize) {
    return fetchSliceList(
        plan, parameters, executionContext, pageSize, null, StatementPurpose.PRIMARY);
  }

  /** Executes and observes a size+1 slice. */
  public <R extends @Nullable Object, P> List<R> fetchSliceList(
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      int pageSize,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    validateRequestedRows(pageSize, executionContext);
    int internalRows;
    try {
      internalRows = Math.addExact(pageSize, 1);
    } catch (ArithmeticException exception) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_OVERFLOW, "pageSize + 1 overflows int", exception);
    }
    return withConnection(
        plan,
        executionContext,
        connection ->
            executeList(
                connection,
                plan,
                parameters,
                executionContext,
                internalRows,
                observation,
                purpose));
  }

  /** Executes content and count on one acquired connection and returns no partial result. */
  public <R extends @Nullable Object, P, C> JdbcPageResult<R> fetchPage(
      CompiledQueryPlan<R, P> contentPlan,
      P contentParameters,
      CompiledQueryPlan<Long, C> countPlan,
      C countParameters,
      ExecutionContext executionContext) {
    return fetchPage(
        contentPlan, contentParameters, countPlan, countParameters, executionContext, null);
  }

  /** Executes and observes content and count on one acquired connection. */
  @SuppressWarnings("Convert2Diamond")
  public <R extends @Nullable Object, P, C> JdbcPageResult<R> fetchPage(
      CompiledQueryPlan<R, P> contentPlan,
      P contentParameters,
      CompiledQueryPlan<Long, C> countPlan,
      C countParameters,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation) {
    Objects.requireNonNull(contentPlan, "contentPlan");
    Objects.requireNonNull(countPlan, "countPlan");
    Objects.requireNonNull(executionContext, "executionContext");
    return withConnection(
        contentPlan,
        executionContext,
        connection -> {
          List<R> items =
              executeList(
                  connection,
                  contentPlan,
                  contentParameters,
                  executionContext,
                  NO_MINIMUM_MAX_ROWS,
                  observation,
                  StatementPurpose.PAGE_CONTENT);
          try {
            JdbcRow<Long> count =
                executeSingleRow(
                    connection,
                    countPlan,
                    countParameters,
                    executionContext,
                    false,
                    true,
                    observation,
                    StatementPurpose.PAGE_COUNT);
            if (!count.present() || count.value() == null) {
              throw QueryValidationException.of(
                  SkisErrorCode.QUERY_RESULT_MAPPING,
                  "count query did not return one non-null row");
            }
            return new JdbcPageResult<R>(items, count.value());
          } catch (PhasedSqlFailure failure) {
            throw translate(countPlan, failure.phase(), failure.sqlException());
          } catch (SQLException failure) {
            throw translate(countPlan, JdbcPhase.EXECUTE, failure);
          }
        });
  }

  /** Opens an explicit cursor that owns its ResultSet, Statement, and acquired connection. */
  public <R extends @Nullable Object, P> JdbcCursor<R> openCursor(
      CompiledQueryPlan<R, P> plan, P parameters, ExecutionContext executionContext) {
    return openCursor(plan, parameters, executionContext, null, StatementPurpose.PRIMARY);
  }

  /** Opens an observed cursor that completes only when its resources really terminate. */
  public <R extends @Nullable Object, P> JdbcCursor<R> openCursor(
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(executionContext, "executionContext");
    Connection connection;
    try {
      connection = connectionProvider.acquire(executionContext);
    } catch (SQLException failure) {
      throw translate(plan, JdbcPhase.ACQUIRE, failure);
    }
    PreparedStatement statement = null;
    StatementObservation statementObservation = null;
    try {
      statementObservation = startStatement(observation, purpose, plan);
      statement =
          prepare(
              connection,
              plan,
              parameters,
              executionContext,
              NO_MINIMUM_MAX_ROWS,
              statementObservation);
      ResultSet resultSet = executeQuery(statement, statementObservation);
      return new DefaultJdbcCursor<>(
          plan,
          executionContext,
          connection,
          statement,
          resultSet,
          observation,
          statementObservation);
    } catch (PhasedSqlFailure failure) {
      RuntimeException primary = translate(plan, failure.phase(), failure.sqlException());
      closeOpenFailure(
          plan, connection, statement, executionContext, statementObservation, primary);
      throw primary;
    } catch (SQLException failure) {
      RuntimeException primary = translate(plan, JdbcPhase.EXECUTE, failure);
      closeOpenFailure(
          plan, connection, statement, executionContext, statementObservation, primary);
      throw primary;
    } catch (RuntimeException | Error failure) {
      closeOpenFailure(
          plan, connection, statement, executionContext, statementObservation, failure);
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
    return fetchOne(plan, parameters, executionContext, null, StatementPurpose.PRIMARY);
  }

  /** Executes and observes a query requiring at most one row. */
  public <R, P> Optional<R> fetchOne(
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    JdbcRow<R> row =
        fetchSingleRow(plan, parameters, executionContext, false, true, observation, purpose);
    return row.present() ? Optional.of(Objects.requireNonNull(row.value())) : Optional.empty();
  }

  private <R extends @Nullable Object, P> JdbcRow<R> fetchSingleRow(
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      boolean nullable,
      boolean checkMultiple,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(executionContext, "executionContext");
    return withConnection(
        plan,
        executionContext,
        connection ->
            executeSingleRow(
                connection,
                plan,
                parameters,
                executionContext,
                nullable,
                checkMultiple,
                observation,
                purpose));
  }

  private <R extends @Nullable Object, P> List<R> executeList(
      Connection connection,
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      int minimumMaxRows,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    StatementObservation statementObservation = startStatement(observation, purpose, plan);
    long readRows = 0L;
    try {
      List<R> results;
      try (PreparedStatement statement =
              prepare(
                  connection,
                  plan,
                  parameters,
                  executionContext,
                  minimumMaxRows,
                  statementObservation);
          ResultSet resultSet = executeQuery(statement, statementObservation)) {
        results = new ArrayList<>();
        while (resultSet.next()) {
          readRows++;
          results.add(plan.rowDecoder().decode(resultSet, rowReadContext));
        }
      }
      completeStatementSucceeded(statementObservation, readRows, unknownRowCount());
      return results;
    } catch (PhasedSqlFailure failure) {
      RuntimeException translated = translate(plan, failure.phase(), failure.sqlException());
      completeStatementFailed(statementObservation, translated, readRows, unknownRowCount());
      throw translated;
    } catch (SQLException failure) {
      RuntimeException translated = translate(plan, JdbcPhase.EXECUTE, failure);
      completeStatementFailed(statementObservation, translated, readRows, unknownRowCount());
      throw translated;
    } catch (RuntimeException | Error failure) {
      completeStatementFailed(statementObservation, failure, readRows, unknownRowCount());
      throw failure;
    }
  }

  private <R extends @Nullable Object, P> JdbcRow<R> executeSingleRow(
      Connection connection,
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      boolean nullable,
      boolean checkMultiple,
      @Nullable OperationObservation observation,
      StatementPurpose purpose)
      throws SQLException {
    int minimumMaxRows = checkMultiple ? FETCH_ONE_CARDINALITY_ROWS : NO_MINIMUM_MAX_ROWS;
    StatementObservation statementObservation = startStatement(observation, purpose, plan);
    long readRows = 0L;
    try {
      JdbcRow<R> row;
      try (PreparedStatement statement =
              prepare(
                  connection,
                  plan,
                  parameters,
                  executionContext,
                  minimumMaxRows,
                  statementObservation);
          ResultSet resultSet = executeQuery(statement, statementObservation)) {
        if (!resultSet.next()) {
          row = JdbcRow.absent();
        } else {
          readRows++;
          row = JdbcRow.present(plan.rowDecoder().decode(resultSet, rowReadContext));
          if (!nullable && row.value() == null) {
            throw QueryValidationException.of(
                SkisErrorCode.QUERY_NON_NULL_RESULT,
                SkisDiagnostics.builder(SkisDiagnosticStage.RESULT_DECODING)
                    .operation(SkisOperation.QUERY)
                    .phase(JdbcPhase.EXECUTE.label())
                    .statementKind(SkisStatementKind.SELECT)
                    .sqlFingerprint(JdbcFingerprint.of(plan.sql()))
                    .dialectId(plan.dialectId())
                    .detail("non-null query decoder returned null")
                    .build());
          }
          if (checkMultiple && resultSet.next()) {
            readRows++;
            throw NonUniqueResultException.of(
                SkisDiagnostics.builder(SkisDiagnosticStage.RESULT_DECODING)
                    .operation(SkisOperation.QUERY)
                    .phase(JdbcPhase.EXECUTE.label())
                    .statementKind(SkisStatementKind.SELECT)
                    .sqlFingerprint(JdbcFingerprint.of(plan.sql()))
                    .dialectId(plan.dialectId())
                    .observedCount(NonUniqueResultException.OBSERVED_ROW_LOWER_BOUND)
                    .detail("query expected at most one row")
                    .build());
          }
        }
      }
      completeStatementSucceeded(statementObservation, readRows, unknownRowCount());
      return row;
    } catch (PhasedSqlFailure failure) {
      RuntimeException translated = translate(plan, failure.phase(), failure.sqlException());
      completeStatementFailed(statementObservation, translated, readRows, unknownRowCount());
      throw translated;
    } catch (SQLException failure) {
      RuntimeException translated = translate(plan, JdbcPhase.EXECUTE, failure);
      completeStatementFailed(statementObservation, translated, readRows, unknownRowCount());
      throw translated;
    } catch (RuntimeException | Error failure) {
      completeStatementFailed(statementObservation, failure, readRows, unknownRowCount());
      throw failure;
    }
  }

  /** Executes a compiled INSERT, UPDATE, or DELETE plan and returns its affected-row count. */
  public <P> int executeUpdate(CompiledMutationPlan<P> plan, P parameters) {
    return executeUpdate(plan, parameters, ExecutionContext.EMPTY);
  }

  /** Executes a compiled mutation plan using an explicit execution context. */
  public <P> int executeUpdate(
      CompiledMutationPlan<P> plan, P parameters, ExecutionContext executionContext) {
    return executeUpdate(plan, parameters, executionContext, null, StatementPurpose.PRIMARY);
  }

  /** Executes and observes a compiled mutation plan. */
  public <P> int executeUpdate(
      CompiledMutationPlan<P> plan,
      P parameters,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(executionContext, "executionContext");
    return withConnection(
        executionContext,
        (phase, failure) ->
            JdbcExecutionException.from(
                phase,
                SkisStatementKind.fromOperationName(plan.operation()),
                plan.dialectId(),
                plan.sql(),
                failure,
                classify(failure)),
        connection -> {
          StatementObservation statementObservation = startStatement(observation, purpose, plan);
          long affectedRows = unknownRowCount();
          try {
            int affected;
            try (PreparedStatement statement =
                prepare(
                    connection,
                    plan.sql(),
                    plan.parameterCount(),
                    plan.parameterBinder(),
                    parameters,
                    executionContext,
                    NO_MINIMUM_MAX_ROWS,
                    statementObservation)) {
              affected = executeUpdate(statement, statementObservation);
              affectedRows = affected;
            }
            completeStatementSucceeded(statementObservation, unknownRowCount(), affected);
            return affected;
          } catch (PhasedSqlFailure failure) {
            RuntimeException translated = translate(plan, failure.phase(), failure.sqlException());
            completeStatementFailed(
                statementObservation, translated, unknownRowCount(), affectedRows);
            throw translated;
          } catch (SQLException failure) {
            RuntimeException translated = translate(plan, JdbcPhase.EXECUTE, failure);
            completeStatementFailed(
                statementObservation, translated, unknownRowCount(), affectedRows);
            throw translated;
          } catch (RuntimeException | Error failure) {
            completeStatementFailed(statementObservation, failure, unknownRowCount(), affectedRows);
            throw failure;
          }
        });
  }

  /** Executes a mutation and returns its detached, immutable rows with an exact row count. */
  @SuppressWarnings("unchecked")
  public <R, P> List<R> executeReturning(
      CompiledMutationPlan<P> plan,
      RowDecoder<R> decoder,
      int columnCount,
      P parameters,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    return (List<R>)
        executeReturningResult(
            plan, decoder, columnCount, false, parameters, executionContext, observation, purpose);
  }

  /** Executes an INSERT and returns exactly one detached row without a collection buffer. */
  @SuppressWarnings("unchecked")
  public <R, P> R executeReturningOne(
      CompiledMutationPlan<P> plan,
      RowDecoder<R> decoder,
      int columnCount,
      P parameters,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    return (R)
        executeReturningResult(
            plan, decoder, columnCount, true, parameters, executionContext, observation, purpose);
  }

  // The typed entrypoints fix the result shape; both share resource and observation ownership.
  private <R, P> Object executeReturningResult(
      CompiledMutationPlan<P> plan,
      RowDecoder<R> decoder,
      int columnCount,
      boolean requireSingleRow,
      P parameters,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(decoder, "decoder");
    Objects.requireNonNull(executionContext, "executionContext");
    ExecutionOptions overrides = executionContext.executionOptions();
    int maxRows =
        overrides.hasMaxRows()
            ? overrides.maxRows()
            : defaultExecutionOptions.hasMaxRows() ? defaultExecutionOptions.maxRows() : 0;
    if (maxRows > 0) {
      throw MutationException.of(
          SkisErrorCode.MUTATION_UNSUPPORTED,
          "mutation RETURNING requires unlimited maxRows for exact affected-row counts");
    }
    return withConnection(
        executionContext,
        (phase, failure) -> translate(plan, phase, failure),
        connection -> {
          StatementObservation statementObservation = startStatement(observation, purpose, plan);
          long read = 0L;
          try {
            R singleResult = null;
            List<R> results = requireSingleRow ? null : new ArrayList<>();
            PreparedStatement statement =
                prepare(
                    connection,
                    plan.sql(),
                    plan.parameterCount(),
                    plan.parameterBinder(),
                    parameters,
                    executionContext,
                    NO_MINIMUM_MAX_ROWS,
                    statementObservation);
            Throwable statementFailure = null;
            try {
              ResultSet resultSet = executeReturningQuery(statement, statementObservation);
              Throwable resultFailure = null;
              try {
                var metadata = resultSet.getMetaData();
                if (metadata == null || metadata.getColumnCount() != columnCount) {
                  throw MutationException.of(
                      SkisErrorCode.MUTATION_RETURNING_SHAPE,
                      "RETURNING column count differs from its compiled layout");
                }
                while (resultSet.next()) {
                  read++;
                  if (requireSingleRow && read > 1) {
                    throw MutationException.of(
                        SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
                        "single-row INSERT RETURNING returned multiple rows");
                  }
                  R value = decoder.decode(resultSet, rowReadContext);
                  if (value == null) {
                    throw MutationException.of(
                        SkisErrorCode.MUTATION_RETURNING_SHAPE,
                        "mutation RETURNING decoder returned null");
                  }
                  if (results == null) {
                    singleResult = value;
                  } else {
                    results.add(value);
                  }
                }
                if (requireSingleRow && read != 1) {
                  throw MutationException.of(
                      SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
                      "single-row INSERT RETURNING must return exactly one row");
                }
              } catch (SQLException | RuntimeException | Error failure) {
                resultFailure = failure;
                throw failure;
              } finally {
                try {
                  resultSet.close();
                } catch (SQLException | RuntimeException | Error failure) {
                  generatedCloseFailure(JdbcPhase.RESULT_SET_CLOSE, failure, resultFailure);
                }
              }
            } catch (SQLException | RuntimeException | Error failure) {
              statementFailure = failure;
              throw failure;
            } finally {
              try {
                statement.close();
              } catch (SQLException | RuntimeException | Error failure) {
                generatedCloseFailure(JdbcPhase.STATEMENT_CLOSE, failure, statementFailure);
              }
            }
            Object completed =
                requireSingleRow
                    ? Objects.requireNonNull(singleResult)
                    : List.copyOf(Objects.requireNonNull(results));
            completeStatementSucceeded(statementObservation, read, read);
            return completed;
          } catch (PhasedSqlFailure failure) {
            RuntimeException translated = translate(plan, failure.phase(), failure.sqlException());
            completeStatementFailed(statementObservation, translated, read, unknownRowCount());
            throw translated;
          } catch (SQLException failure) {
            RuntimeException translated = translate(plan, JdbcPhase.EXECUTE, failure);
            completeStatementFailed(statementObservation, translated, read, unknownRowCount());
            throw translated;
          } catch (RuntimeException | Error failure) {
            completeStatementFailed(statementObservation, failure, read, unknownRowCount());
            throw failure;
          }
        });
  }

  private static ResultSet executeReturningQuery(
      PreparedStatement statement, @Nullable StatementObservation observation) throws SQLException {
    try {
      if (statement.getMaxRows() > 0) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_UNSUPPORTED,
            "configured statement truncates mutation RETURNING rows");
      }
    } catch (SQLException failure) {
      throw new PhasedSqlFailure(JdbcPhase.CONFIGURE, failure);
    }
    return executeQuery(statement, observation);
  }

  /** Executes one INSERT and reads its explicitly requested, single non-null generated key. */
  public <P> Object executeGeneratedKey(
      CompiledMutationPlan<P> plan,
      P parameters,
      String physicalIdColumn,
      JdbcTypeCodec<?> keyCodec,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(physicalIdColumn, "physicalIdColumn");
    Objects.requireNonNull(keyCodec, "keyCodec");
    Objects.requireNonNull(executionContext, "executionContext");
    return withConnection(
        executionContext,
        (phase, failure) -> translate(plan, phase, failure),
        connection -> {
          StatementObservation statementObservation = startStatement(observation, purpose, plan);
          long affected = unknownRowCount();
          long read = 0L;
          try {
            Object key;
            PreparedStatement statement =
                prepare(
                    connection,
                    plan.sql(),
                    plan.parameterCount(),
                    plan.parameterBinder(),
                    parameters,
                    executionContext,
                    NO_MINIMUM_MAX_ROWS,
                    statementObservation,
                    physicalIdColumn);
            Throwable statementFailure = null;
            try {
              affected = executeUpdate(statement, statementObservation);
              if (affected != 1) {
                throw MutationException.of(
                    SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
                    "generated-key INSERT must affect exactly one row");
              }
              ResultSet keys = statement.getGeneratedKeys();
              if (keys == null) {
                throw invalidGeneratedKey();
              }
              Throwable keyFailure = null;
              try {
                var keyMetadata = keys.getMetaData();
                if (keyMetadata == null
                    || keyMetadata.getColumnCount() != 1
                    || !integralGeneratedKeyType(keyMetadata.getColumnType(1))
                    || !keys.next()) {
                  throw invalidGeneratedKey();
                }
                read = 1L;
                try {
                  key = keyCodec.read(keys, 1, rowReadContext);
                } catch (QueryValidationException failure) {
                  // Integer codecs classify query mapping overflow; writes retain the JDBC cause.
                  if (failure.getCause() instanceof SQLException driverFailure) {
                    throw driverFailure;
                  }
                  throw invalidGeneratedKey();
                }
                if (key == null) {
                  throw invalidGeneratedKey();
                }
                if (keys.next()) {
                  read = 2L;
                  throw invalidGeneratedKey();
                }
              } catch (SQLException | RuntimeException | Error failure) {
                keyFailure = failure;
                throw failure;
              } finally {
                try {
                  keys.close();
                } catch (SQLException | RuntimeException | Error failure) {
                  generatedCloseFailure(JdbcPhase.RESULT_SET_CLOSE, failure, keyFailure);
                }
              }
            } catch (SQLException | RuntimeException | Error failure) {
              statementFailure = failure;
              throw failure;
            } finally {
              try {
                statement.close();
              } catch (SQLException | RuntimeException | Error failure) {
                generatedCloseFailure(JdbcPhase.STATEMENT_CLOSE, failure, statementFailure);
              }
            }
            completeStatementSucceeded(statementObservation, read, affected);
            return key;
          } catch (PhasedSqlFailure failure) {
            RuntimeException translated = translate(plan, failure.phase(), failure.sqlException());
            completeStatementFailed(statementObservation, translated, read, affected);
            throw translated;
          } catch (SQLException failure) {
            RuntimeException translated = translate(plan, JdbcPhase.EXECUTE, failure);
            completeStatementFailed(statementObservation, translated, read, affected);
            throw translated;
          } catch (RuntimeException | Error failure) {
            completeStatementFailed(statementObservation, failure, read, affected);
            throw failure;
          }
        });
  }

  private static MutationException invalidGeneratedKey() {
    return MutationException.of(
        SkisErrorCode.MUTATION_GENERATED_KEY_INVALID,
        "generated keys must contain one integral column and one non-null key");
  }

  private static boolean integralGeneratedKeyType(int jdbcType) {
    return jdbcType == java.sql.Types.TINYINT
        || jdbcType == java.sql.Types.SMALLINT
        || jdbcType == java.sql.Types.INTEGER
        || jdbcType == java.sql.Types.BIGINT;
  }

  private static void generatedCloseFailure(
      JdbcPhase phase, Throwable failure, @Nullable Throwable primary) {
    if (primary != null) {
      Throwable cause =
          primary instanceof PhasedSqlFailure phased ? phased.sqlException() : primary;
      if (cause != failure) {
        cause.addSuppressed(failure);
      }
    } else if (failure instanceof SQLException sqlFailure) {
      throw new PhasedSqlFailure(phase, sqlFailure);
    } else if (failure instanceof RuntimeException runtimeFailure) {
      throw runtimeFailure;
    } else {
      throw (Error) failure;
    }
  }

  private static <R extends @Nullable Object> @Nullable StatementObservation startStatement(
      @Nullable OperationObservation observation,
      StatementPurpose purpose,
      CompiledQueryPlan<R, ?> plan) {
    return observation == null
        ? null
        : observation.startStatement(
            purpose,
            SkisStatementKind.SELECT,
            plan.dialectId(),
            JdbcFingerprint.of(plan.sql()),
            plan.sql());
  }

  private static @Nullable StatementObservation startStatement(
      @Nullable OperationObservation observation,
      StatementPurpose purpose,
      CompiledMutationPlan<?> plan) {
    return observation == null
        ? null
        : observation.startStatement(
            purpose,
            SkisStatementKind.fromOperationName(plan.operation()),
            plan.dialectId(),
            JdbcFingerprint.of(plan.sql()),
            plan.sql());
  }

  private static ResultSet executeQuery(
      PreparedStatement statement, @Nullable StatementObservation observation) throws SQLException {
    if (observation != null) {
      observation.executeStarted();
    }
    try {
      return statement.executeQuery();
    } finally {
      if (observation != null) {
        observation.executeCompleted();
      }
    }
  }

  private static int executeUpdate(
      PreparedStatement statement, @Nullable StatementObservation observation) throws SQLException {
    if (observation != null) {
      observation.executeStarted();
    }
    try {
      return statement.executeUpdate();
    } finally {
      if (observation != null) {
        observation.executeCompleted();
      }
    }
  }

  private static void completeStatementSucceeded(
      @Nullable StatementObservation observation, long readRows, long affectedRows) {
    if (observation != null) {
      observation.completeSucceeded(ExecutionTermination.COMPLETED, readRows, affectedRows);
    }
  }

  private static void completeStatementFailed(
      @Nullable StatementObservation observation,
      Throwable failure,
      long readRows,
      long affectedRows) {
    if (observation != null) {
      try {
        observation.completeFailed(failure, SkisDiagnosticStage.EXECUTION, readRows, affectedRows);
      } catch (RuntimeException | Error completionFailure) {
        if (completionFailure != failure) {
          completionFailure.addSuppressed(failure);
        }
        throw completionFailure;
      }
    }
  }

  private static long unknownRowCount() {
    return StatementCompletedEvent.UNKNOWN_ROW_COUNT;
  }

  private <R extends @Nullable Object, P> PreparedStatement prepare(
      Connection connection,
      CompiledQueryPlan<R, P> plan,
      P parameters,
      ExecutionContext executionContext,
      int minimumMaxRows,
      @Nullable StatementObservation observation)
      throws SQLException {
    return prepare(
        connection,
        plan.sql(),
        plan.parameterCount(),
        plan.parameterBinder(),
        parameters,
        executionContext,
        minimumMaxRows,
        observation);
  }

  private <P> PreparedStatement prepare(
      Connection connection,
      String sql,
      int parameterCount,
      ParameterBinder<P> parameterBinder,
      P parameters,
      ExecutionContext executionContext,
      int minimumMaxRows,
      @Nullable StatementObservation observation)
      throws SQLException {
    return prepare(
        connection,
        sql,
        parameterCount,
        parameterBinder,
        parameters,
        executionContext,
        minimumMaxRows,
        observation,
        null);
  }

  private <P> PreparedStatement prepare(
      Connection connection,
      String sql,
      int parameterCount,
      ParameterBinder<P> parameterBinder,
      P parameters,
      ExecutionContext executionContext,
      int minimumMaxRows,
      @Nullable StatementObservation observation,
      @Nullable String physicalIdColumn)
      throws SQLException {
    ExecutionOptions statementOptions =
        executionContext == ExecutionContext.EMPTY
            ? ExecutionOptions.NONE
            : Objects.requireNonNull(
                executionContext.executionOptions(), "executionContext options");
    boolean optionsConfigured =
        defaultExecutionOptionsConfigured
            || (statementOptions != ExecutionOptions.NONE && !statementOptions.isEmpty());
    String actualSql = optionsConfigured ? taggedSql(sql, statementOptions) : sql;
    PreparedStatement statement =
        physicalIdColumn == null
            ? connection.prepareStatement(actualSql)
            : connection.prepareStatement(actualSql, new String[] {physicalIdColumn});
    try {
      int expectedNextIndex = parameterCount + 1;
      int nextIndex;
      if (loggingOptions.parameters() && SafeLog.debugEnabled(PARAMETERS)) {
        ParameterSummary summary =
            new ParameterSummary(
                loggingOptions.parameterRedactor(), parameterCount, observation != null);
        try {
          nextIndex = parameterBinder.bindObserved(statement, 1, parameters, writeContext, summary);
          if (nextIndex == expectedNextIndex) {
            summary.publish(
                PARAMETERS,
                observation == null ? JdbcFingerprint.of(sql) : observation.sqlFingerprint(),
                observation == null ? 0L : observation.operationId(),
                observation == null ? 0L : observation.statementId());
          }
        } finally {
          if (observation != null) {
            observation.excludeLoggingNanos(summary.loggingNanos());
          }
        }
      } else {
        nextIndex = parameterBinder.bind(statement, 1, parameters, writeContext);
      }
      if (nextIndex != expectedNextIndex) {
        throw new PhasedSqlFailure(
            JdbcPhase.BIND,
            new SQLException("compiled parameter binder returned an unexpected next index"));
      }
      try {
        int queryTimeoutSeconds =
            optionsConfigured
                ? applyExecutionOptions(statement, statementOptions, minimumMaxRows)
                : UNCONFIGURED_QUERY_TIMEOUT;
        connectionProvider.configureStatement(statement, executionContext, queryTimeoutSeconds);
      } catch (SQLException failure) {
        throw new PhasedSqlFailure(JdbcPhase.CONFIGURE, failure);
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

  private int applyExecutionOptions(
      PreparedStatement statement, ExecutionOptions statementOptions, int minimumMaxRows)
      throws SQLException {
    int queryTimeoutSeconds = UNCONFIGURED_QUERY_TIMEOUT;
    if (statementOptions.hasStatementTimeout()) {
      queryTimeoutSeconds = statementOptions.queryTimeoutSeconds();
      statement.setQueryTimeout(queryTimeoutSeconds);
    } else if (defaultExecutionOptions.hasStatementTimeout()) {
      queryTimeoutSeconds = defaultExecutionOptions.queryTimeoutSeconds();
      statement.setQueryTimeout(queryTimeoutSeconds);
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
    return queryTimeoutSeconds;
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
      throw failureTranslator.translate(JdbcPhase.ACQUIRE, failure);
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
      RuntimeException translated = failureTranslator.translate(JdbcPhase.EXECUTE, failure);
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
          throw failureTranslator.translate(JdbcPhase.RELEASE, releaseFailure);
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
      CompiledQueryPlan<?, ?> plan, JdbcPhase phase, SQLException failure) {
    return QueryExecutionException.from(
        phase, plan.dialectId(), plan.sql(), failure, classify(failure));
  }

  private JdbcExecutionException translate(
      CompiledMutationPlan<?> plan, JdbcPhase phase, SQLException failure) {
    return JdbcExecutionException.from(
        phase,
        SkisStatementKind.fromOperationName(plan.operation()),
        plan.dialectId(),
        plan.sql(),
        failure,
        classify(failure));
  }

  private void closeOpenFailure(
      CompiledQueryPlan<?, ?> plan,
      Connection connection,
      @Nullable PreparedStatement statement,
      ExecutionContext executionContext,
      @Nullable StatementObservation statementObservation,
      Throwable primary) {
    if (statement != null) {
      try {
        statement.close();
      } catch (SQLException failure) {
        primary.addSuppressed(translate(plan, JdbcPhase.STATEMENT_CLOSE, failure));
      } catch (RuntimeException | Error failure) {
        primary.addSuppressed(failure);
      }
    }
    Throwable pendingFailure = primary;
    try {
      completeStatementFailed(statementObservation, primary, unknownRowCount(), unknownRowCount());
    } catch (RuntimeException | Error listenerFailure) {
      pendingFailure = listenerFailure;
      throw listenerFailure;
    } finally {
      try {
        connectionProvider.release(connection, executionContext);
      } catch (SQLException failure) {
        pendingFailure.addSuppressed(translate(plan, JdbcPhase.RELEASE, failure));
      } catch (RuntimeException | Error failure) {
        if (failure != pendingFailure) {
          pendingFailure.addSuppressed(failure);
        }
      }
    }
  }

  private final class DefaultJdbcCursor<R extends @Nullable Object> implements JdbcCursor<R> {

    private final CompiledQueryPlan<R, ?> plan;
    private final CursorResources<R> resources;
    private final Cleaner.Cleanable cleanable;
    private R current = unpositionedCursorValue();

    private DefaultJdbcCursor(
        CompiledQueryPlan<R, ?> plan,
        ExecutionContext executionContext,
        Connection connection,
        PreparedStatement statement,
        ResultSet resultSet,
        @Nullable OperationObservation observation,
        @Nullable StatementObservation statementObservation) {
      this.plan = plan;
      this.resources =
          new CursorResources<>(
              plan,
              executionContext,
              connection,
              statement,
              resultSet,
              observation,
              statementObservation);
      this.cleanable = CursorCleanerHolder.INSTANCE.register(this, resources);
    }

    @Override
    public boolean advance() {
      if (resources.isClosed()) {
        return false;
      }
      current = unpositionedCursorValue();
      try {
        if (!resources.resultSet.next()) {
          Throwable failure = closeResources(null, ExecutionTermination.EXHAUSTED);
          if (failure != null) {
            throw runtimeFailure(failure);
          }
          return false;
        }
        if (resources.statementObservation != null) {
          resources.readRows++;
        }
        current = plan.rowDecoder().decode(resources.resultSet, rowReadContext);
        return true;
      } catch (SQLException failure) {
        RuntimeException primary = translate(plan, JdbcPhase.EXECUTE, failure);
        throw runtimeFailure(closeResources(primary, ExecutionTermination.FAILED));
      } catch (RuntimeException | Error failure) {
        throw runtimeFailure(closeResources(failure, ExecutionTermination.FAILED));
      }
    }

    @Override
    public R current() {
      if (current == CURSOR_NOT_POSITIONED || resources.isClosed()) {
        throw new IllegalStateException(
            "current() requires a successful advance() and an open cursor");
      }
      return current;
    }

    @Override
    public void fail(Throwable failure) {
      Objects.requireNonNull(failure, "failure");
      if (resources.isClosed()) {
        return;
      }
      Throwable result = closeResources(failure, ExecutionTermination.FAILED);
      if (result instanceof Error error && result != failure) {
        throw error;
      }
      if (result instanceof RuntimeException runtime && result != failure) {
        throw runtime;
      }
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
      Throwable failure = closeResources(null, ExecutionTermination.CLOSED_EARLY);
      if (failure instanceof Error error) {
        throw error;
      }
      if (failure instanceof RuntimeException runtime) {
        throw runtime;
      }
    }

    private @Nullable Throwable closeResources(
        @Nullable Throwable primary, ExecutionTermination termination) {
      current = unpositionedCursorValue();
      Throwable result = resources.closeResources(primary, termination);
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

  @SuppressWarnings("unchecked")
  private static <R extends @Nullable Object> @NonNull R unpositionedCursorValue() {
    // R erases to Object. The private non-null sentinel is only stored while unpositioned and
    // current() rejects it before exposing any value; positioned values come from RowDecoder<R>.
    return (R) CURSOR_NOT_POSITIONED;
  }

  private final class CursorResources<R extends @Nullable Object> implements Runnable {

    private final CompiledQueryPlan<R, ?> plan;
    private final ExecutionContext executionContext;
    private final Connection connection;
    private final PreparedStatement statement;
    private final ResultSet resultSet;
    private final @Nullable OperationObservation observation;
    private final @Nullable StatementObservation statementObservation;
    private boolean closed;
    // Only the resource-owning cursor thread accesses this counter; the Cleaner never reads it.
    private long readRows;

    private CursorResources(
        CompiledQueryPlan<R, ?> plan,
        ExecutionContext executionContext,
        Connection connection,
        PreparedStatement statement,
        ResultSet resultSet,
        @Nullable OperationObservation observation,
        @Nullable StatementObservation statementObservation) {
      this.plan = plan;
      this.executionContext = executionContext;
      this.connection = connection;
      this.statement = statement;
      this.resultSet = resultSet;
      this.observation = observation;
      this.statementObservation = statementObservation;
    }

    private synchronized boolean isClosed() {
      return closed;
    }

    private synchronized @Nullable Throwable closeResources(
        @Nullable Throwable primary, ExecutionTermination termination) {
      if (closed) {
        return primary;
      }
      closed = true;
      Throwable result = primary;
      result = closeResultSet(result);
      result = closeStatement(result);
      result = completeStatement(result, termination);
      result = releaseConnection(result);
      result = completeOperation(result, termination);
      return result;
    }

    @Override
    public synchronized void run() {
      if (closed) {
        return;
      }
      if (SafeLog.warnEnabled(LOGGER)) {
        String fingerprint = JdbcFingerprint.of(plan.sql());
        SafeLog.warn(
            LOGGER,
            "JDBC cursor was abandoned without close [dialect="
                + SafeLog.text(plan.dialectId(), 128)
                + ", sqlFingerprint="
                + fingerprint
                + "]");
      }
      // JDBC cleanup is intentionally not attempted from the Cleaner thread. Providers backed by
      // external transaction managers may require release on the acquiring thread.
    }

    private @Nullable Throwable closeResultSet(@Nullable Throwable primary) {
      try {
        resultSet.close();
        return primary;
      } catch (SQLException failure) {
        return append(primary, translate(plan, JdbcPhase.RESULT_SET_CLOSE, failure));
      } catch (RuntimeException | Error failure) {
        return append(primary, failure);
      }
    }

    private @Nullable Throwable closeStatement(@Nullable Throwable primary) {
      try {
        statement.close();
        return primary;
      } catch (SQLException failure) {
        return append(primary, translate(plan, JdbcPhase.STATEMENT_CLOSE, failure));
      } catch (RuntimeException | Error failure) {
        return append(primary, failure);
      }
    }

    private @Nullable Throwable releaseConnection(@Nullable Throwable primary) {
      try {
        connectionProvider.release(connection, executionContext);
        return primary;
      } catch (SQLException failure) {
        return append(primary, translate(plan, JdbcPhase.RELEASE, failure));
      } catch (RuntimeException | Error failure) {
        return append(primary, failure);
      }
    }

    private @Nullable Throwable completeStatement(
        @Nullable Throwable primary, ExecutionTermination termination) {
      StatementObservation active = statementObservation;
      if (active == null) {
        return primary;
      }
      try {
        if (primary == null) {
          active.completeSucceeded(termination, readRows, unknownRowCount());
        } else {
          active.completeFailed(
              primary, SkisDiagnosticStage.RESOURCE_CLOSE, readRows, unknownRowCount());
        }
        return primary;
      } catch (Error listenerFailure) {
        return promote(listenerFailure, primary);
      } catch (RuntimeException listenerFailure) {
        return append(primary, listenerFailure);
      }
    }

    private @Nullable Throwable completeOperation(
        @Nullable Throwable primary, ExecutionTermination termination) {
      OperationObservation active = observation;
      if (active == null) {
        return primary;
      }
      try {
        if (primary == null) {
          active.completeSucceeded(termination);
        } else {
          active.completeFailed(primary, SkisDiagnosticStage.RESOURCE_CLOSE);
        }
        return primary;
      } catch (Error listenerFailure) {
        return promote(listenerFailure, primary);
      } catch (RuntimeException listenerFailure) {
        return append(primary, listenerFailure);
      }
    }

    private Throwable promote(Error failure, @Nullable Throwable primary) {
      if (primary != null && primary != failure) {
        failure.addSuppressed(primary);
      }
      return failure;
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
    RuntimeException translate(JdbcPhase phase, SQLException failure);
  }

  private static final class PhasedSqlFailure extends RuntimeException {

    private final JdbcPhase phase;
    private final SQLException sqlException;

    private PhasedSqlFailure(JdbcPhase phase, SQLException sqlException) {
      super(sqlException);
      this.phase = phase;
      this.sqlException = sqlException;
    }

    private JdbcPhase phase() {
      return phase;
    }

    private SQLException sqlException() {
      return sqlException;
    }
  }
}
