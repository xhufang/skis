package io.skis.sql.ast;

import io.skis.metadata.PropertyMeta;
import java.util.Objects;

/** Immutable typed column reference backed by generated property metadata. */
public final class ColumnExpression<E, V> {

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
}
