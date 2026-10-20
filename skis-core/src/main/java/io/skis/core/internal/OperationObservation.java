package io.skis.core.internal;

import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisStatementKind;
import io.skis.core.internal.ExecutionListenerDispatcher.FailureSnapshot;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.OperationCompletedEvent;
import io.skis.core.spi.StatementPurpose;
import io.skis.core.spi.StatementStartedEvent;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Invocation-scoped operation lifecycle explicitly passed across query/mutation and JDBC layers.
 */
public final class OperationObservation {

  private final ExecutionListenerDispatcher dispatcher;
  private final long operationId;
  private final ExecutionOperationKind operationKind;
  private final String dialectId;
  private final long startedNanos;
  private long callbackNanos;
  private boolean completed;

  OperationObservation(
      ExecutionListenerDispatcher dispatcher,
      long operationId,
      ExecutionOperationKind operationKind,
      String dialectId,
      long startedNanos) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.operationId = operationId;
    this.operationKind = Objects.requireNonNull(operationKind, "operationKind");
    this.dialectId = Objects.requireNonNull(dialectId, "dialectId");
    this.startedNanos = startedNanos;
  }

  public long operationId() {
    return operationId;
  }

  public ExecutionOperationKind operationKind() {
    return operationKind;
  }

  public String dialectId() {
    return dialectId;
  }

  /**
   * Emits one on-demand compilation event and excludes callback time from the operation duration.
   */
  public void compilationCompleted(
      StatementPurpose purpose,
      SkisStatementKind statementKind,
      long compilationDurationNanos,
      @Nullable String sqlFingerprint,
      @Nullable String sqlTemplate,
      @Nullable Throwable failure) {
    addCallbackNanos(
        dispatcher.compilationCompleted(
            this,
            purpose,
            statementKind,
            compilationDurationNanos,
            sqlFingerprint,
            sqlTemplate,
            failure));
  }

  /** Starts one SQL attempt after a connection has been acquired. */
  public StatementObservation startStatement(
      StatementPurpose purpose,
      SkisStatementKind statementKind,
      String dialectId,
      String sqlFingerprint,
      String sqlTemplate) {
    if (completed) {
      throw new IllegalStateException("an operation cannot start SQL after completion");
    }
    long statementStartedNanos = System.nanoTime();
    long statementId = dispatcher.nextId();
    StatementObservation statement =
        new StatementObservation(
            this,
            dispatcher,
            statementId,
            purpose,
            statementKind,
            dialectId,
            sqlFingerprint,
            sqlTemplate,
            statementStartedNanos);
    long dispatched =
        dispatcher.statementStarted(
            new StatementStartedEvent(
                operationId,
                statementId,
                operationKind,
                purpose,
                statementKind,
                dialectId,
                sqlFingerprint,
                sqlTemplate));
    statement.addCallbackNanos(dispatched);
    addCallbackNanos(dispatched);
    return statement;
  }

  /** Completes one materialized or write operation successfully. */
  public void completeSucceeded(ExecutionTermination termination) {
    complete(null, SkisDiagnosticStage.EXECUTION, termination);
  }

  /** Completes one failed operation using safe framework diagnostics when available. */
  public void completeFailed(Throwable failure, SkisDiagnosticStage fallbackStage) {
    complete(
        Objects.requireNonNull(failure, "failure"),
        Objects.requireNonNull(fallbackStage, "fallbackStage"),
        ExecutionTermination.FAILED);
  }

  void addCallbackNanos(long nanos) {
    callbackNanos = saturatedAdd(callbackNanos, nanos);
  }

  private void complete(
      @Nullable Throwable failure,
      SkisDiagnosticStage fallbackStage,
      ExecutionTermination termination) {
    if (completed) {
      return;
    }
    completed = true;
    FailureSnapshot snapshot = ExecutionListenerDispatcher.failure(failure, fallbackStage);
    dispatcher.operationCompleted(
        new OperationCompletedEvent(
            operationId,
            operationKind,
            dialectId,
            snapshot.outcome(),
            termination,
            ExecutionListenerDispatcher.elapsed(startedNanos, callbackNanos),
            snapshot.stage(),
            snapshot.diagnostics()));
  }

  private static long saturatedAdd(long left, long right) {
    long result = left + right;
    return result < 0L ? Long.MAX_VALUE : result;
  }
}
