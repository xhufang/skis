package io.skis.query;

import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Type-safe generated query column that builds immutable value-separated predicates. */
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

  public SqlType sqlType() {
    return expression.sqlType();
  }

  public Nullability nullability() {
    return expression.nullability();
  }

  public boolean nullable() {
    return expression.nullable();
  }

  public QueryPredicate<E> eq(@Nullable V value) {
    return comparison(ComparisonOperator.EQUAL, value);
  }

  public QueryPredicate<E> ne(@Nullable V value) {
    return comparison(ComparisonOperator.NOT_EQUAL, value);
  }

  public QueryPredicate<E> gt(@Nullable V value) {
    requireOrdering("gt");
    return comparison(ComparisonOperator.GREATER_THAN, value);
  }

  public QueryPredicate<E> ge(@Nullable V value) {
    requireOrdering("ge");
    return comparison(ComparisonOperator.GREATER_THAN_OR_EQUAL, value);
  }

  public QueryPredicate<E> lt(@Nullable V value) {
    requireOrdering("lt");
    return comparison(ComparisonOperator.LESS_THAN, value);
  }

  public QueryPredicate<E> le(@Nullable V value) {
    requireOrdering("le");
    return comparison(ComparisonOperator.LESS_THAN_OR_EQUAL, value);
  }

  public QueryPredicate<E> isNull() {
    return QueryPredicate.nullCheck(this, NullOperator.IS_NULL);
  }

  public QueryPredicate<E> isNotNull() {
    return QueryPredicate.nullCheck(this, NullOperator.IS_NOT_NULL);
  }

  public QueryPredicate<E> between(@Nullable V lower, @Nullable V upper) {
    requireOrdering("between");
    return QueryPredicate.between(
        this,
        requireValue("between lower bound", lower),
        requireValue("between upper bound", upper));
  }

  public QueryPredicate<E> like(String pattern) {
    if (javaType() != String.class || !sqlType().supportsLike()) {
      throw unsupported("like");
    }
    V typedPattern = requireValue("like", pattern);
    return QueryPredicate.like(this, typedPattern);
  }

  public QueryPredicate<E> in(Collection<? extends V> values) {
    return membership(values, false);
  }

  public QueryPredicate<E> notIn(Collection<? extends V> values) {
    return membership(values, true);
  }

  ColumnExpression<E, V> expression() {
    return expression;
  }

  private QueryPredicate<E> comparison(ComparisonOperator operator, @Nullable V value) {
    return QueryPredicate.comparison(this, operator, requireValue(operator.name(), value));
  }

  private QueryPredicate<E> membership(Collection<? extends V> values, boolean negated) {
    Objects.requireNonNull(values, "values");
    List<V> copy = new ArrayList<>(values.size());
    int index = 0;
    for (V value : values) {
      copy.add(requireValue((negated ? "notIn" : "in") + " value " + index, value));
      index++;
    }
    return QueryPredicate.in(this, List.copyOf(copy), negated);
  }

  private V requireValue(String operation, @Nullable Object value) {
    if (value == null) {
      throw new QueryValidationException(
          operation
              + "(null) is not supported for property '"
              + property().name()
              + "'; use isNull()/isNotNull() for SQL null tests");
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
    return javaType().cast(value);
  }

  private void requireOrdering(String operation) {
    if (!sqlType().isOrderable()) {
      throw unsupported(operation);
    }
  }

  private QueryValidationException unsupported(String operation) {
    return new QueryValidationException(
        operation
            + " is not supported for property '"
            + property().name()
            + "' with SQL type "
            + sqlType());
  }
}
