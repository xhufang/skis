package io.skis.sql.ast;

import java.util.Objects;

/** Immutable {@code IS NULL} or {@code IS NOT NULL} predicate. */
public record NullPredicate(SqlExpression<?> operand, NullOperator operator)
    implements SqlPredicate {

  public NullPredicate {
    Objects.requireNonNull(operand, "operand");
    Objects.requireNonNull(operator, "operator");
  }

  @Override
  public Nullability nullability() {
    return Nullability.NON_NULL;
  }

  @Override
  public boolean nullable() {
    return false;
  }
}
