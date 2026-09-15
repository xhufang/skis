package io.skis.sql.ast;

import java.util.Objects;

/**
 * Immutable SQL scalar subquery over one complete SELECT subtree.
 *
 * <p>The child must expose exactly one physical output column. This contract does not prove that
 * the child returns at most one row: zero rows evaluate to SQL {@code NULL}, one row evaluates to
 * its selected value, and a multi-row result remains a database cardinality error.
 */
public record ScalarSubqueryExpression<T>(SelectStatement subquery) implements SqlExpression<T> {

  /** Creates a conservatively nullable scalar expression from one single-column SELECT. */
  public ScalarSubqueryExpression(SelectStatement subquery) {
    this.subquery = Objects.requireNonNull(subquery, "subquery");
    SemanticValidator.validateScalarSubquery(subquery);
  }

  /** Complete single-column SELECT evaluated in scalar context by the database. */
  @Override
  public SelectStatement subquery() {
    return subquery;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Class<T> javaType() {
    return (Class<T>) subquery.selections().getFirst().javaType();
  }

  @Override
  public SqlType sqlType() {
    return subquery.selections().getFirst().sqlType();
  }

  /** A zero-row child produces SQL NULL even when its selected expression is declared non-null. */
  @Override
  public Nullability nullability() {
    return Nullability.NULLABLE;
  }

  @Override
  public boolean nullable() {
    return true;
  }
}
