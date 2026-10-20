package io.skis.core.spi;

import java.util.Objects;

/** Immutable safe snapshot emitted when a terminal operation enters its observation range. */
public record OperationStartedEvent(
    long operationId, ExecutionOperationKind operationKind, String dialectId) {

  /** Validates the public immutable event contract. */
  public OperationStartedEvent {
    ExecutionEventContracts.positive(operationId, "operationId");
    Objects.requireNonNull(operationKind, "operationKind");
    ExecutionEventContracts.text(dialectId, "dialectId");
  }
}
