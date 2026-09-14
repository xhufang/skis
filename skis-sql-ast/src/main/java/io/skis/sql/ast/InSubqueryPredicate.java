package io.skis.sql.ast;

import java.util.Objects;

/**
 * Immutable SQL {@code IN (SELECT ...)} or {@code NOT IN (SELECT ...)} predicate.
 *
 * <p>The child is the ordinary immutable {@link SelectStatement}; this node neither materializes
 * its rows nor rewrites membership to a Join or EXISTS predicate. Exactly one physical child output
 * is required so hidden selections cannot silently change row-value arity.
 */
public record InSubqueryPredicate<T>(
    SqlExpression<T> value, SelectStatement subquery, boolean negated) implements SqlPredicate {

  /** Creates a subquery membership predicate after validating its one-column value contract. */
  public InSubqueryPredicate(SqlExpression<T> value, SelectStatement subquery, boolean negated) {
    this.value = Objects.requireNonNull(value, "value");
    this.subquery = Objects.requireNonNull(subquery, "subquery");
    this.negated = negated;
    SemanticValidator.validateInSubquery(value, subquery);
  }

  /** Left-hand value tested against the child result set. */
  @Override
  public SqlExpression<T> value() {
    return value;
  }

  /** Complete one-column SELECT evaluated by the database. */
  @Override
  public SelectStatement subquery() {
    return subquery;
  }

  /** Whether this node represents {@code NOT IN}. */
  @Override
  public boolean negated() {
    return negated;
  }

  @Override
  public Nullability nullability() {
    return value.nullability().union(subquery.selections().getFirst().nullability());
  }

  @Override
  public boolean nullable() {
    return nullability().isNullable();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other
                instanceof
                InSubqueryPredicate<?>(
                    SqlExpression<?> value1,
                    SelectStatement subquery1,
                    boolean negated1)
            && negated == negated1
            && value.equals(value1)
            && subquery.equals(subquery1);
  }

  @Override
  public int hashCode() {
    int result = value.hashCode();
    result = 31 * result + subquery.hashCode();
    return 31 * result + Boolean.hashCode(negated);
  }
}
