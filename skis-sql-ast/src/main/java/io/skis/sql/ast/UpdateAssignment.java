package io.skis.sql.ast;

import java.util.Objects;

/** Immutable assignment in an UPDATE SET clause. */
public record UpdateAssignment<T>(ColumnExpression<?, T> column, SqlExpression<T> value) {

  /** Validates the assignment through the centralized semantic rules. */
  public UpdateAssignment {
    Objects.requireNonNull(column, "column");
    Objects.requireNonNull(value, "value");
    SemanticValidator.validateAssignment(column, value, "UPDATE");
  }
}
