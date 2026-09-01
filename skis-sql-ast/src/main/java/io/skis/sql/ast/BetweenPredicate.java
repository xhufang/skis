package io.skis.sql.ast;

import java.util.Objects;

/** Immutable inclusive SQL {@code BETWEEN} predicate. */
public record BetweenPredicate<T>(
    SqlExpression<T> value, SqlExpression<T> lower, SqlExpression<T> upper)
    implements SqlPredicate {

  public BetweenPredicate {
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(lower, "lower");
    Objects.requireNonNull(upper, "upper");
    SemanticValidator.validateBetween(value, lower, upper);
  }

  @Override
  public Nullability nullability() {
    return value.nullability().union(lower.nullability()).union(upper.nullability());
  }

  @Override
  public boolean nullable() {
    return nullability().isNullable();
  }
}
