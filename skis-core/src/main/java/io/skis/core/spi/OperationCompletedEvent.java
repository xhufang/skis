package io.skis.core.spi;

import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisDiagnostics;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable safe snapshot emitted after an operation and its owned cleanup really finish. */
public record OperationCompletedEvent(
    long operationId,
    ExecutionOperationKind operationKind,
    String dialectId,
    ExecutionOutcome outcome,
    ExecutionTermination termination,
    long durationNanos,
    @Nullable SkisDiagnosticStage failureStage,
    @Nullable SkisDiagnostics diagnostics) {

  /** Validates the public immutable event contract. */
  public OperationCompletedEvent {
    ExecutionEventContracts.positive(operationId, "operationId");
    Objects.requireNonNull(operationKind, "operationKind");
    ExecutionEventContracts.text(dialectId, "dialectId");
    ExecutionEventContracts.nonNegative(durationNanos);
    ExecutionEventContracts.termination(operationKind, outcome, termination);
    ExecutionEventContracts.completion(outcome, failureStage, diagnostics);
  }
}
