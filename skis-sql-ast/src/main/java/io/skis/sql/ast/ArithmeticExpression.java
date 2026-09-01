package io.skis.sql.ast;

import java.util.Objects;

/** Immutable binary arithmetic expression with an explicitly validated result type. */
public record ArithmeticExpression<T>(
    SqlExpression<T> left, ArithmeticOperator operator, SqlExpression<T> right)
    implements SqlExpression<T> {

  /** Creates an arithmetic expression after centralized semantic validation. */
  public ArithmeticExpression {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(right, "right");
    SemanticValidator.validateArithmetic(left, operator, right);
  }

  @Override
  public Class<T> javaType() {
    return left.javaType();
  }

  @Override
  public SqlType sqlType() {
    return left.sqlType();
  }

  @Override
  public Nullability nullability() {
    return left.nullability().union(right.nullability());
  }

  @Override
  public boolean nullable() {
    return nullability().isNullable();
  }
}
