package io.skis.query;

import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import java.util.Objects;

/** Type-safe generated query column whose value predicates keep values outside the SQL AST. */
public final class QueryColumn<E, V> {

  private final ColumnExpression<E, V> expression;

  QueryColumn(ColumnExpression<E, V> expression) {
    this.expression = Objects.requireNonNull(expression, "expression");
  }

  public PropertyMeta<E, V> property() {
    return expression.property();
  }

  public Class<V> javaType() {
    return expression.javaType();
  }

  public boolean nullable() {
    return expression.nullable();
  }

  /** Creates a bound equality predicate without storing the value in the structural SQL AST. */
  public QueryPredicate eq(V value) {
    if (value == null) {
      throw new QueryValidationException(
          "eq(null) is not supported for property '" + property().name() + "'");
    }
    if (!javaType().isInstance(value)) {
      throw new QueryValidationException(
          "property '"
              + property().name()
              + "' requires "
              + javaType().getTypeName()
              + " but received "
              + value.getClass().getTypeName());
    }
    return QueryPredicate.equal(this, value);
  }

  ColumnExpression<E, V> expression() {
    return expression;
  }
}
