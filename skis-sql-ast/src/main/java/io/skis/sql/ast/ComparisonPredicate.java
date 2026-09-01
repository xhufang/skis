package io.skis.sql.ast;

import java.util.Objects;

/**
 * Immutable comparison between two expressions with the same Java representation.
 *
 * @param <T> common Java representation of both operands
 */
public record ComparisonPredicate<T>(
    SqlExpression<T> left, ComparisonOperator operator, SqlExpression<T> right)
    implements SqlPredicate {

  /** Validates and creates a comparison predicate. */
  public ComparisonPredicate {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(right, "right");
    SemanticValidator.validateComparison(left, operator, right);
  }

  @Override
  public Nullability nullability() {
    return left.nullability().union(right.nullability());
  }

  @Override
  public boolean nullable() {
    return left.nullable() || right.nullable();
  }
}
