package io.skis.sql.ast;

import io.skis.metadata.PropertyMeta;
import java.util.Objects;

/**
 * Immutable typed column reference backed by canonical generated property metadata.
 *
 * <p>Property metadata is a symbol owned by an {@link io.skis.metadata.EntityMeta}; expression
 * equality therefore uses property identity rather than accidental value equality.
 */
public final class ColumnExpression<E, V> implements SqlExpression<V> {

  private final TableExpression<E> table;
  private final PropertyMeta<E, V> property;

  ColumnExpression(TableExpression<E> table, PropertyMeta<E, V> property) {
    this.table = Objects.requireNonNull(table, "table");
    this.property = Objects.requireNonNull(property, "property");
  }

  public TableExpression<E> table() {
    return table;
  }

  public PropertyMeta<E, V> property() {
    return property;
  }

  @Override
  public Class<V> javaType() {
    return property.javaType();
  }

  @Override
  public SqlType sqlType() {
    return SqlType.fromJavaType(property.javaType());
  }

  @Override
  public Nullability nullability() {
    return Nullability.of(property.column().nullable());
  }

  @Override
  public boolean nullable() {
    return property.column().nullable();
  }

  /** Creates a typed equality predicate without capturing a runtime parameter value. */
  public ComparisonPredicate<V> eq(SqlExpression<V> other) {
    return new ComparisonPredicate<>(this, ComparisonOperator.EQUAL, other);
  }

  public ComparisonPredicate<V> ne(SqlExpression<V> other) {
    return new ComparisonPredicate<>(this, ComparisonOperator.NOT_EQUAL, other);
  }

  public ComparisonPredicate<V> gt(SqlExpression<V> other) {
    return new ComparisonPredicate<>(this, ComparisonOperator.GREATER_THAN, other);
  }

  public ComparisonPredicate<V> ge(SqlExpression<V> other) {
    return new ComparisonPredicate<>(this, ComparisonOperator.GREATER_THAN_OR_EQUAL, other);
  }

  public ComparisonPredicate<V> lt(SqlExpression<V> other) {
    return new ComparisonPredicate<>(this, ComparisonOperator.LESS_THAN, other);
  }

  public ComparisonPredicate<V> le(SqlExpression<V> other) {
    return new ComparisonPredicate<>(this, ComparisonOperator.LESS_THAN_OR_EQUAL, other);
  }

  public NullPredicate isNull() {
    return new NullPredicate(this, NullOperator.IS_NULL);
  }

  public NullPredicate isNotNull() {
    return new NullPredicate(this, NullOperator.IS_NOT_NULL);
  }

  public BetweenPredicate<V> between(SqlExpression<V> lower, SqlExpression<V> upper) {
    return new BetweenPredicate<>(this, lower, upper);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof ColumnExpression<?, ?> column
            && table.equals(column.table)
            && property == column.property;
  }

  @Override
  public int hashCode() {
    return 31 * table.hashCode() + System.identityHashCode(property);
  }
}
