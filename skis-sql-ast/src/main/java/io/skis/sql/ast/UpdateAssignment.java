package io.skis.sql.ast;

import java.util.Objects;

/** Immutable assignment in an UPDATE SET clause. */
public record UpdateAssignment<T>(ColumnExpression<?, T> column, SqlExpression<T> value) {

  /** Validates that the assigned value has the same Java type as its target column. */
  public UpdateAssignment {
    Objects.requireNonNull(column, "column");
    Objects.requireNonNull(value, "value");
    if (!column.javaType().equals(value.javaType())) {
      throw new IllegalArgumentException(
          "UPDATE value type does not match column '" + column.property().name() + "'");
    }
  }
}
