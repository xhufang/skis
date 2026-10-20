package io.skis.core.spi;

import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisStatementKind;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable safe snapshot emitted after one SQL attempt and its statement resources finish. */
public record StatementCompletedEvent(
    long operationId,
    long statementId,
    ExecutionOperationKind operationKind,
    StatementPurpose purpose,
    SkisStatementKind statementKind,
    String dialectId,
    String sqlFingerprint,
    String sqlTemplate,
    ExecutionOutcome outcome,
    ExecutionTermination termination,
    long durationNanos,
    long executeDurationNanos,
    long readRowCount,
    long affectedRowCount,
    @Nullable SkisDiagnosticStage failureStage,
    @Nullable SkisDiagnostics diagnostics) {

  /** Indicates that the driver execute call was not reached or could not be measured. */
  public static final long UNKNOWN_DURATION_NANOS = -1L;

  /** Indicates that a row count is not applicable or not known. */
  public static final long UNKNOWN_ROW_COUNT = -1L;

  /** Validates the public immutable event contract. */
  public StatementCompletedEvent {
    ExecutionEventContracts.positive(operationId, "operationId");
    ExecutionEventContracts.positive(statementId, "statementId");
    Objects.requireNonNull(operationKind, "operationKind");
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(statementKind, "statementKind");
    ExecutionEventContracts.text(dialectId, "dialectId");
    ExecutionEventContracts.text(sqlFingerprint, "sqlFingerprint");
    ExecutionEventContracts.text(sqlTemplate, "sqlTemplate");
    ExecutionEventContracts.nonNegative(durationNanos);
    ExecutionEventContracts.unknownOrNonNegative(
        executeDurationNanos, UNKNOWN_DURATION_NANOS, "executeDurationNanos");
    ExecutionEventContracts.unknownOrNonNegative(readRowCount, UNKNOWN_ROW_COUNT, "readRowCount");
    ExecutionEventContracts.unknownOrNonNegative(
        affectedRowCount, UNKNOWN_ROW_COUNT, "affectedRowCount");
    ExecutionEventContracts.termination(operationKind, outcome, termination);
    ExecutionEventContracts.completion(outcome, failureStage, diagnostics);
  }
}
