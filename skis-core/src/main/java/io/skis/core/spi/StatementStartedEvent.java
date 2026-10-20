package io.skis.core.spi;

import io.skis.core.SkisStatementKind;
import java.util.Objects;

/** Immutable safe snapshot emitted before one SQL prepare/configure/bind attempt. */
public record StatementStartedEvent(
    long operationId,
    long statementId,
    ExecutionOperationKind operationKind,
    StatementPurpose purpose,
    SkisStatementKind statementKind,
    String dialectId,
    String sqlFingerprint,
    String sqlTemplate) {

  /** Validates the public immutable event contract. */
  public StatementStartedEvent {
    ExecutionEventContracts.positive(operationId, "operationId");
    ExecutionEventContracts.positive(statementId, "statementId");
    Objects.requireNonNull(operationKind, "operationKind");
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(statementKind, "statementKind");
    ExecutionEventContracts.text(dialectId, "dialectId");
    ExecutionEventContracts.text(sqlFingerprint, "sqlFingerprint");
    ExecutionEventContracts.text(sqlTemplate, "sqlTemplate");
  }
}
