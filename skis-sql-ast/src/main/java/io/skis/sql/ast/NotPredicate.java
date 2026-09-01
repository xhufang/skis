package io.skis.sql.ast;

import java.util.Objects;

/** Immutable SQL three-valued logical negation. */
public record NotPredicate(SqlPredicate operand) implements SqlPredicate {

  public NotPredicate {
    Objects.requireNonNull(operand, "operand");
  }

  @Override
  public Nullability nullability() {
    return operand.nullability();
  }

  @Override
  public boolean nullable() {
    return operand.nullable();
  }
}
