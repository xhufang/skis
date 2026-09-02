package io.skis.sql.ast;

import java.util.Objects;

/** Immutable ORDER BY expression, direction, and null placement. */
public record OrderByItem(
    SqlExpression<?> expression, OrderDirection direction, NullOrder nullOrder) {

  public OrderByItem {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(nullOrder, "nullOrder");
    if (!expression.sqlType().isOrderable()) {
      throw new IllegalArgumentException(
          "ORDER BY expression SQL type is not orderable: " + expression.sqlType());
    }
  }
}
