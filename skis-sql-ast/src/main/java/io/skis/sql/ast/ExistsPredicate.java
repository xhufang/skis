package io.skis.sql.ast;

import java.util.Objects;

/**
 * Immutable SQL {@code EXISTS} or {@code NOT EXISTS} predicate over one complete SELECT subtree.
 *
 * <p>Existence depends only on whether the subquery produces a row. The selected values, their
 * nullability, and duplicate rows are deliberately preserved and do not affect this predicate's
 * non-null Boolean result.
 */
public record ExistsPredicate(SelectStatement subquery, boolean negated) implements SqlPredicate {

  /** Creates an existence predicate without rewriting or pruning the supplied SELECT. */
  public ExistsPredicate(SelectStatement subquery, boolean negated) {
    this.subquery = Objects.requireNonNull(subquery, "subquery");
    this.negated = negated;
  }

  /** SELECT subtree evaluated for row existence. */
  @Override
  public SelectStatement subquery() {
    return subquery;
  }

  /** Whether this node represents {@code NOT EXISTS}. */
  @Override
  public boolean negated() {
    return negated;
  }

  @Override
  public Nullability nullability() {
    return Nullability.NON_NULL;
  }

  @Override
  public boolean nullable() {
    return false;
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof ExistsPredicate(SelectStatement subquery1, boolean negated1)
            && negated == negated1
            && subquery.equals(subquery1);
  }

  @Override
  public int hashCode() {
    return 31 * subquery.hashCode() + Boolean.hashCode(negated);
  }
}
