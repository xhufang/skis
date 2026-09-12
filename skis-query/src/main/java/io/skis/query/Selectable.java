package io.skis.query;

import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

/**
 * Framework-owned read-only SQL selection expression.
 *
 * <p>The sealed contract prevents application implementations from injecting arbitrary SQL.
 * Generated columns and framework-created standard expressions share this contract so selection,
 * predicates, ordering, and generated projections do not need expression-specific API families.
 */
public sealed interface Selectable<V> permits ExpressionSelectable, NonNullSelectable, QueryColumn {

  /** Returns the boxed Java value type produced by this expression. */
  Class<V> javaType();

  /** Returns the portable SQL type produced by this expression. */
  SqlType sqlType();

  /** Returns the expression's declared nullability before query-local Join propagation. */
  Nullability nullability();

  /** Returns the immutable SQL AST represented by this selection. */
  SqlExpression<V> expression();

  /** Compares this expression with one captured non-null value. */
  default QueryCondition eq(@Nullable V value) {
    return SelectableSupport.valueComparison(this, io.skis.sql.ast.ComparisonOperator.EQUAL, value);
  }

  /** Compares this expression with a separately bound reusable query parameter. */
  default QueryCondition eq(QueryParameter<V> parameter) {
    return SelectableSupport.parameterComparison(
        this, io.skis.sql.ast.ComparisonOperator.EQUAL, parameter);
  }

  /** Compares two expressions with the same Java value type. */
  default QueryCondition eq(Selectable<V> other) {
    return SelectableSupport.expressionComparison(
        this, io.skis.sql.ast.ComparisonOperator.EQUAL, other);
  }

  /** Compares this expression with one captured non-null value. */
  default QueryCondition ne(@Nullable V value) {
    return SelectableSupport.valueComparison(
        this, io.skis.sql.ast.ComparisonOperator.NOT_EQUAL, value);
  }

  /** Compares this expression with a separately bound reusable query parameter. */
  default QueryCondition ne(QueryParameter<V> parameter) {
    return SelectableSupport.parameterComparison(
        this, io.skis.sql.ast.ComparisonOperator.NOT_EQUAL, parameter);
  }

  /** Compares two expressions with the same Java value type. */
  default QueryCondition ne(Selectable<V> other) {
    return SelectableSupport.expressionComparison(
        this, io.skis.sql.ast.ComparisonOperator.NOT_EQUAL, other);
  }

  /** Applies a portable ordered comparison to one captured non-null value. */
  default QueryCondition gt(@Nullable V value) {
    return SelectableSupport.valueComparison(
        this, io.skis.sql.ast.ComparisonOperator.GREATER_THAN, value);
  }

  /** Applies an ordered comparison to a separately bound reusable query parameter. */
  default QueryCondition gt(QueryParameter<V> parameter) {
    return SelectableSupport.parameterComparison(
        this, io.skis.sql.ast.ComparisonOperator.GREATER_THAN, parameter);
  }

  /** Applies a portable ordered comparison to another expression. */
  default QueryCondition gt(Selectable<V> other) {
    return SelectableSupport.expressionComparison(
        this, io.skis.sql.ast.ComparisonOperator.GREATER_THAN, other);
  }

  /** Applies a portable ordered comparison to one captured non-null value. */
  default QueryCondition ge(@Nullable V value) {
    return SelectableSupport.valueComparison(
        this, io.skis.sql.ast.ComparisonOperator.GREATER_THAN_OR_EQUAL, value);
  }

  /** Applies an ordered comparison to a separately bound reusable query parameter. */
  default QueryCondition ge(QueryParameter<V> parameter) {
    return SelectableSupport.parameterComparison(
        this, io.skis.sql.ast.ComparisonOperator.GREATER_THAN_OR_EQUAL, parameter);
  }

  /** Applies a portable ordered comparison to another expression. */
  default QueryCondition ge(Selectable<V> other) {
    return SelectableSupport.expressionComparison(
        this, io.skis.sql.ast.ComparisonOperator.GREATER_THAN_OR_EQUAL, other);
  }

  /** Applies a portable ordered comparison to one captured non-null value. */
  default QueryCondition lt(@Nullable V value) {
    return SelectableSupport.valueComparison(
        this, io.skis.sql.ast.ComparisonOperator.LESS_THAN, value);
  }

  /** Applies an ordered comparison to a separately bound reusable query parameter. */
  default QueryCondition lt(QueryParameter<V> parameter) {
    return SelectableSupport.parameterComparison(
        this, io.skis.sql.ast.ComparisonOperator.LESS_THAN, parameter);
  }

  /** Applies a portable ordered comparison to another expression. */
  default QueryCondition lt(Selectable<V> other) {
    return SelectableSupport.expressionComparison(
        this, io.skis.sql.ast.ComparisonOperator.LESS_THAN, other);
  }

  /** Applies a portable ordered comparison to one captured non-null value. */
  default QueryCondition le(@Nullable V value) {
    return SelectableSupport.valueComparison(
        this, io.skis.sql.ast.ComparisonOperator.LESS_THAN_OR_EQUAL, value);
  }

  /** Applies an ordered comparison to a separately bound reusable query parameter. */
  default QueryCondition le(QueryParameter<V> parameter) {
    return SelectableSupport.parameterComparison(
        this, io.skis.sql.ast.ComparisonOperator.LESS_THAN_OR_EQUAL, parameter);
  }

  /** Applies a portable ordered comparison to another expression. */
  default QueryCondition le(Selectable<V> other) {
    return SelectableSupport.expressionComparison(
        this, io.skis.sql.ast.ComparisonOperator.LESS_THAN_OR_EQUAL, other);
  }

  /** Tests whether this expression evaluates to SQL {@code NULL}. */
  default QueryCondition isNull() {
    return SelectableSupport.nullCheck(this, io.skis.sql.ast.NullOperator.IS_NULL);
  }

  /** Tests whether this expression evaluates to a non-null SQL value. */
  default QueryCondition isNotNull() {
    return SelectableSupport.nullCheck(this, io.skis.sql.ast.NullOperator.IS_NOT_NULL);
  }

  /** Applies a portable inclusive range comparison. */
  default QueryCondition between(@Nullable V lower, @Nullable V upper) {
    return SelectableSupport.between(this, lower, upper);
  }

  /** Applies an inclusive range whose bounds are supplied by a parameter environment. */
  default QueryCondition between(QueryParameter<V> lower, QueryParameter<V> upper) {
    return SelectableSupport.betweenParameters(this, lower, upper);
  }

  /** Applies portable SQL {@code LIKE}; only character expressions are accepted. */
  default QueryCondition like(String pattern) {
    return SelectableSupport.like(this, pattern);
  }

  /** Applies SQL {@code LIKE} with a separately bound reusable query parameter. */
  default QueryCondition like(QueryParameter<V> pattern) {
    return SelectableSupport.likeParameter(this, pattern);
  }

  /** Applies SQL set membership to captured non-null values. */
  default QueryCondition in(Collection<? extends V> values) {
    return SelectableSupport.membership(this, values, false);
  }

  /** Applies SQL set membership to separately bound reusable query parameters. */
  default QueryCondition inParameters(Collection<? extends QueryParameter<V>> parameters) {
    return SelectableSupport.parameterMembership(this, parameters, false);
  }

  /** Applies negated SQL set membership to captured non-null values. */
  default QueryCondition notIn(Collection<? extends V> values) {
    return SelectableSupport.membership(this, values, true);
  }

  /** Applies negated SQL set membership to separately bound reusable query parameters. */
  default QueryCondition notInParameters(Collection<? extends QueryParameter<V>> parameters) {
    return SelectableSupport.parameterMembership(this, parameters, true);
  }

  /** Creates ascending ordering using the database default null placement. */
  default SortSpecification asc() {
    return SelectableSupport.order(this, SortDirection.ASC);
  }

  /** Creates descending ordering using the database default null placement. */
  default SortSpecification desc() {
    return SelectableSupport.order(this, SortDirection.DESC);
  }
}
