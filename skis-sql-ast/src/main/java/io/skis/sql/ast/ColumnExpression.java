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
  public boolean nullable() {
    return property.column().nullable();
  }

  /** Creates a typed equality predicate without capturing a runtime parameter value. */
  public ComparisonPredicate<V> eq(SqlExpression<V> other) {
    return new ComparisonPredicate<>(this, ComparisonOperator.EQUAL, other);
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
