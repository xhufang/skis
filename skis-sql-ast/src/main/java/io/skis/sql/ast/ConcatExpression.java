package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/** Immutable SQL-standard character concatenation expression. */
public record ConcatExpression(List<SqlExpression<String>> operands)
    implements SqlExpression<String> {

  /** Defensively copies and validates two or more character operands. */
  public ConcatExpression {
    Objects.requireNonNull(operands, "operands");
    operands = List.copyOf(operands);
    if (operands.size() < 2) {
      throw new IllegalArgumentException("concatenation requires at least two operands");
    }
    operands.forEach(operand -> Objects.requireNonNull(operand, "concatenation operand"));
    SemanticValidator.validateConcat(operands);
  }

  @Override
  public Class<String> javaType() {
    return String.class;
  }

  @Override
  public SqlType sqlType() {
    return SqlType.VARCHAR;
  }

  @Override
  public Nullability nullability() {
    return operands.stream().anyMatch(SqlExpression::nullable)
        ? Nullability.NULLABLE
        : Nullability.NON_NULL;
  }

  @Override
  public boolean nullable() {
    return nullability().isNullable();
  }
}
