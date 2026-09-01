package io.skis.sql.ast;

import java.util.Objects;

/** Immutable numeric expression that advances a value by one. */
public record IncrementExpression<T>(SqlExpression<T> operand) implements SqlExpression<T> {

  /** Creates an increment expression for a numeric operand. */
  public IncrementExpression(SqlExpression<T> operand) {
    this.operand = Objects.requireNonNull(operand, "operand");
    SemanticValidator.validateIncrement(operand);
  }

  @Override
  public Class<T> javaType() {
    return operand.javaType();
  }

  @Override
  public SqlType sqlType() {
    return operand.sqlType();
  }

  @Override
  public Nullability nullability() {
    return operand.nullability();
  }

  @Override
  public boolean nullable() {
    return operand.nullable();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof IncrementExpression<?>(SqlExpression<?> operand1)
            && operand.equals(operand1);
  }
}
