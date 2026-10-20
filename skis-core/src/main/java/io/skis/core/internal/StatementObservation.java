package io.skis.core.internal;

import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisStatementKind;
import io.skis.core.internal.ExecutionListenerDispatcher.FailureSnapshot;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.StatementCompletedEvent;
import io.skis.core.spi.StatementPurpose;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Invocation-scoped SQL lifecycle owned by one {@link OperationObservation}. */
public final class StatementObservation {

  private final OperationObservation operation;
  private final ExecutionListenerDispatcher dispatcher;
  private final long statementId;
  private final StatementPurpose purpose;
  private final SkisStatementKind statementKind;
  private final String dialectId;
  private final String sqlFingerprint;
  private final String sqlTemplate;
  private final long startedNanos;
  private long callbackNanos;
  private long executeStartedNanos;
  private boolean executeActive;
  private long executeDurationNanos = StatementCompletedEvent.UNKNOWN_DURATION_NANOS;
  private boolean completed;

  StatementObservation(
      OperationObservation operation,
      ExecutionListenerDispatcher dispatcher,
      long statementId,
      StatementPurpose purpose,
      SkisStatementKind statementKind,
      String dialectId,
      String sqlFingerprint,
      String sqlTemplate,
      long startedNanos) {
    this.operation = Objects.requireNonNull(operation, "operation");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.statementId = statementId;
    this.purpose = Objects.requireNonNull(purpose, "purpose");
    this.statementKind = Objects.requireNonNull(statementKind, "statementKind");
    this.dialectId = Objects.requireNonNull(dialectId, "dialectId");
    this.sqlFingerprint = Objects.requireNonNull(sqlFingerprint, "sqlFingerprint");
    this.sqlTemplate = Objects.requireNonNull(sqlTemplate, "sqlTemplate");
    this.startedNanos = startedNanos;
  }

  /** Marks the exact start of the JDBC driver execute call. */
  public void executeStarted() {
    if (executeActive) {
      throw new IllegalStateException(
          "executeCompleted must be called before executeStarted again");
    }
    executeStartedNanos = System.nanoTime();
    executeActive = true;
  }

  /** Marks the exact end of the JDBC driver execute call, including a throwing call. */
  public void executeCompleted() {
    if (!executeActive) {
      throw new IllegalStateException("executeStarted must be called first");
    }
    executeDurationNanos = Math.max(0L, System.nanoTime() - executeStartedNanos);
    executeActive = false;
  }

  /** Completes one SQL lifecycle successfully after its ResultSet and Statement are closed. */
  public void completeSucceeded(
      ExecutionTermination termination, long readRowCount, long affectedRowCount) {
    complete(null, SkisDiagnosticStage.EXECUTION, termination, readRowCount, affectedRowCount);
  }

  /** Completes one failed SQL lifecycle after its ResultSet and Statement cleanup. */
  public void completeFailed(
      Throwable failure,
      SkisDiagnosticStage fallbackStage,
      long readRowCount,
      long affectedRowCount) {
    complete(
        Objects.requireNonNull(failure, "failure"),
        Objects.requireNonNull(fallbackStage, "fallbackStage"),
        ExecutionTermination.FAILED,
        readRowCount,
        affectedRowCount);
  }

  void addCallbackNanos(long nanos) {
    callbackNanos = saturatedAdd(callbackNanos, nanos);
  }

  private void complete(
      @Nullable Throwable failure,
      SkisDiagnosticStage fallbackStage,
      ExecutionTermination termination,
      long readRowCount,
      long affectedRowCount) {
    if (completed) {
      return;
    }
    completed = true;
    FailureSnapshot snapshot = ExecutionListenerDispatcher.failure(failure, fallbackStage);
    long dispatched =
        dispatcher.statementCompleted(
            new StatementCompletedEvent(
                operation.operationId(),
                statementId,
                operation.operationKind(),
                purpose,
                statementKind,
                dialectId,
                sqlFingerprint,
                sqlTemplate,
                snapshot.outcome(),
                termination,
                ExecutionListenerDispatcher.elapsed(startedNanos, callbackNanos),
                executeDurationNanos,
                readRowCount,
                affectedRowCount,
                snapshot.stage(),
                snapshot.diagnostics()));
    operation.addCallbackNanos(dispatched);
  }

  private static long saturatedAdd(long left, long right) {
    long result = left + right;
    return result < 0L ? Long.MAX_VALUE : result;
  }
}
