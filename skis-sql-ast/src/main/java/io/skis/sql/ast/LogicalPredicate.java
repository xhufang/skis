package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/** Immutable boolean combination of two or more predicates. */
public record LogicalPredicate(LogicalOperator operator, List<SqlPredicate> operands)
    implements SqlPredicate {

  /** Validates and defensively copies the predicate operands. */
  public LogicalPredicate {
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(operands, "operands");
    operands = List.copyOf(operands);
    if (operands.size() < 2) {
      throw new IllegalArgumentException("a logical predicate requires at least two operands");
    }
    operands.forEach(operand -> Objects.requireNonNull(operand, "logical operand"));
  }

  /** Creates an AND predicate in encounter order. */
  public static LogicalPredicate and(List<? extends SqlPredicate> operands) {
    return new LogicalPredicate(LogicalOperator.AND, List.copyOf(operands));
  }

  /** Creates an OR predicate in encounter order. */
  public static LogicalPredicate or(List<? extends SqlPredicate> operands) {
    return new LogicalPredicate(LogicalOperator.OR, List.copyOf(operands));
  }

  @Override
  public Nullability nullability() {
    return operands.stream().anyMatch(SqlExpression::nullable)
        ? Nullability.NULLABLE
        : Nullability.NON_NULL;
  }

  @Override
  public boolean nullable() {
    return operands.stream().anyMatch(SqlExpression::nullable);
  }
}
