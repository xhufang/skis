package io.skis.sql.ast;

/**
 * A deterministic SQL boolean predicate produced by portable normalization.
 *
 * <p>This node is used when a structure has a known truth value independent of any row or bound
 * value, for example an empty {@code IN} list. It never carries a parameter and is rendered as
 * {@code TRUE} or {@code FALSE}.
 */
public record BooleanPredicate(boolean value) implements SqlPredicate {

  @Override
  public Nullability nullability() {
    return Nullability.NON_NULL;
  }

  @Override
  public boolean nullable() {
    return false;
  }
}
