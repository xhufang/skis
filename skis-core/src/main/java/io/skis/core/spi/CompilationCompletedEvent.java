package io.skis.core.spi;

import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisStatementKind;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable safe snapshot emitted after one actual plan compilation. */
public record CompilationCompletedEvent(
    long operationId,
    CompilationOrigin origin,
    ExecutionOperationKind operationKind,
    StatementPurpose purpose,
    SkisStatementKind statementKind,
    String dialectId,
    ExecutionOutcome outcome,
    long durationNanos,
    @Nullable String sqlFingerprint,
    @Nullable String sqlTemplate,
    @Nullable SkisDiagnosticStage failureStage,
    @Nullable SkisDiagnostics diagnostics) {

  /** Operation ID used by executor prewarming, which has no terminal invocation. */
  public static final long NO_OPERATION_ID = 0L;

  /** Validates the public immutable event contract. */
  public CompilationCompletedEvent {
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(operationKind, "operationKind");
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(statementKind, "statementKind");
    ExecutionEventContracts.text(dialectId, "dialectId");
    ExecutionEventContracts.nonNegative(durationNanos);
    ExecutionEventContracts.optionalText(sqlFingerprint, "sqlFingerprint");
    ExecutionEventContracts.optionalText(sqlTemplate, "sqlTemplate");
    if (origin == CompilationOrigin.PREWARM) {
      if (operationId != NO_OPERATION_ID) {
        throw new IllegalArgumentException("PREWARM compilation must use NO_OPERATION_ID");
      }
    } else {
      ExecutionEventContracts.positive(operationId, "operationId");
    }
    if ((sqlFingerprint == null) != (sqlTemplate == null)) {
      throw new IllegalArgumentException("sqlFingerprint and sqlTemplate must be present together");
    }
    if (outcome == ExecutionOutcome.SUCCEEDED && sqlTemplate == null) {
      throw new IllegalArgumentException(
          "successful compilation requires SQL template and fingerprint");
    }
    ExecutionEventContracts.completion(outcome, failureStage, diagnostics);
  }
}
