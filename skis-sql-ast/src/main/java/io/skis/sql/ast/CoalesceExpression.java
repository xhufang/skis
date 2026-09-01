package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/** Immutable portable SQL {@code COALESCE} expression. */
public record CoalesceExpression<T>(List<SqlExpression<T>> operands)
    implements SqlExpression<T> {

  /** Defensively copies and validates two or more compatible operands. */
  public CoalesceExpression {
    Objects.requireNonNull(operands, "operands");
    operands = List.copyOf(operands);
    if (operands.size() < 2) {
      throw new IllegalArgumentException("COALESCE requires at least two operands");
    }
    operands.forEach(operand -> Objects.requireNonNull(operand, "COALESCE operand"));
    SemanticValidator.validateCoalesce(operands);
  }

  @Override
  public Class<T> javaType() {
    return operands.getFirst().javaType();
  }

  @Override
  public SqlType sqlType() {
    return operands.getFirst().sqlType();
  }

  @Override
  public Nullability nullability() {
    return operands.stream().anyMatch(operand -> !operand.nullable())
        ? Nullability.NON_NULL
        : Nullability.NULLABLE;
  }

  @Override
  public boolean nullable() {
    return nullability().isNullable();
  }
}
