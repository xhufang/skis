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

/** Shared predicate and ordering DSL for generated nullable and non-null columns. */
public abstract sealed class QueryColumn<E, V> implements Selectable<V>
    permits NonNullQueryColumn, NullableQueryColumn {

  private final ColumnExpression<E, V> expression;

  QueryColumn(ColumnExpression<E, V> expression) {
    this.expression = Objects.requireNonNull(expression, "expression");
  }

  public final PropertyMeta<E, V> property() {
    return expression.property();
  }

  public final Class<V> javaType() {
    return expression.javaType();
  }

  public final SqlType sqlType() {
    return expression.sqlType();
  }

  public final Nullability nullability() {
    return expression.nullability();
  }

  public final boolean nullable() {
    return expression.nullable();
  }

  public final QueryPredicate<E> eq(@Nullable V value) {
    return comparison(ComparisonOperator.EQUAL, value);
  }

  /** Compares this column with another table column using portable equality rules. */
  public final <O> QueryCondition eq(QueryColumn<O, V> other) {
    return columnComparison(ComparisonOperator.EQUAL, other);
  }

  public final QueryPredicate<E> ne(@Nullable V value) {
    return comparison(ComparisonOperator.NOT_EQUAL, value);
  }

  /** Compares this column with another table column using portable inequality rules. */
  public final <O> QueryCondition ne(QueryColumn<O, V> other) {
    return columnComparison(ComparisonOperator.NOT_EQUAL, other);
  }

  public final QueryPredicate<E> gt(@Nullable V value) {
    requireOrdering("gt");
    return comparison(ComparisonOperator.GREATER_THAN, value);
  }

  /** Compares this column with another table column using portable ordering rules. */
  public final <O> QueryCondition gt(QueryColumn<O, V> other) {
    return columnComparison(ComparisonOperator.GREATER_THAN, other);
  }

  public final QueryPredicate<E> ge(@Nullable V value) {
    requireOrdering("ge");
    return comparison(ComparisonOperator.GREATER_THAN_OR_EQUAL, value);
  }

  /** Compares this column with another table column using portable ordering rules. */
  public final <O> QueryCondition ge(QueryColumn<O, V> other) {
    return columnComparison(ComparisonOperator.GREATER_THAN_OR_EQUAL, other);
  }

  public final QueryPredicate<E> lt(@Nullable V value) {
    requireOrdering("lt");
    return comparison(ComparisonOperator.LESS_THAN, value);
  }

  /** Compares this column with another table column using portable ordering rules. */
  public final <O> QueryCondition lt(QueryColumn<O, V> other) {
    return columnComparison(ComparisonOperator.LESS_THAN, other);
  }

  public final QueryPredicate<E> le(@Nullable V value) {
    requireOrdering("le");
    return comparison(ComparisonOperator.LESS_THAN_OR_EQUAL, value);
  }

  /** Compares this column with another table column using portable ordering rules. */
  public final <O> QueryCondition le(QueryColumn<O, V> other) {
    return columnComparison(ComparisonOperator.LESS_THAN_OR_EQUAL, other);
  }

  public final QueryPredicate<E> isNull() {
    return QueryPredicate.nullCheck(this, NullOperator.IS_NULL);
  }

  public final QueryPredicate<E> isNotNull() {
    return QueryPredicate.nullCheck(this, NullOperator.IS_NOT_NULL);
  }

  public final QueryPredicate<E> between(@Nullable V lower, @Nullable V upper) {
    requireOrdering("between");
    return QueryPredicate.between(
        this,
        requireValue("between lower bound", lower),
        requireValue("between upper bound", upper));
  }

  public final QueryPredicate<E> like(String pattern) {
    if (javaType() != String.class || !sqlType().supportsLike()) {
      throw unsupported("like");
    }
    V typedPattern = requireValue("like", pattern);
    return QueryPredicate.like(this, typedPattern);
  }

  public final QueryPredicate<E> in(Collection<? extends V> values) {
    return membership(values, false);
  }

  public final QueryPredicate<E> notIn(Collection<? extends V> values) {
    return membership(values, true);
  }

  /** Creates ascending ordering using the database default null placement. */
  public final SortSpecification<E> asc() {
    requireOrdering("asc");
    return new SortSpecification<>(this, SortDirection.ASC, NullPlacement.DIALECT_DEFAULT);
  }

  /** Creates descending ordering using the database default null placement. */
  public final SortSpecification<E> desc() {
    requireOrdering("desc");
    return new SortSpecification<>(this, SortDirection.DESC, NullPlacement.DIALECT_DEFAULT);
  }

  @Override
  public final ColumnExpression<E, V> expression() {
    return expression;
  }

  final QueryTable<E> table() {
    return (QueryTable<E>) expression.table();
  }

  private QueryPredicate<E> comparison(ComparisonOperator operator, @Nullable V value) {
    return QueryPredicate.comparison(this, operator, requireValue(operator.name(), value));
  }

  private <O> QueryCondition columnComparison(
      ComparisonOperator operator, QueryColumn<O, V> other) {
    return FrameworkQueryCondition.comparison(
        this, operator, Objects.requireNonNull(other, "other"));
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
