package io.skis.query;

import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.query.internal.QueryTableModel;
import java.util.List;
import java.util.Optional;

/** AST-independent base for generated tables; the canonical model stays inside the framework. */
public abstract class QueryTable<E> {
  private final QueryTableModel<E> model;

  protected QueryTable(EntityMeta<E> entity) {
    this.model = new QueryTableModel<>(this, entity);
  }

  protected QueryTable(EntityMeta<E> entity, String alias) {
    this.model = new QueryTableModel<>(this, entity, alias);
  }

  public final EntityMeta<E> entity() {
    return model.entity();
  }

  public final Optional<String> alias() {
    return model.alias();
  }

  public abstract QueryTable<E> as(String alias);

  /** Canonical DSL columns in metadata order, for metadata-driven selection. */
  public final List<? extends Selectable<?>> columns() {
    return model.columns();
  }

  protected final <V> Selectable<V> queryColumn(PropertyMeta<E, V> property) {
    return model.queryColumn(property);
  }

  protected final <V> NonNullSelectable<V> nonNullQueryColumn(PropertyMeta<E, V> property) {
    return model.nonNullQueryColumn(property);
  }

  protected final <V> Selectable<V> nullableQueryColumn(PropertyMeta<E, V> property) {
    return model.nullableQueryColumn(property);
  }

  @Override
  public final boolean equals(@org.jspecify.annotations.Nullable Object other) {
    return this == other || other instanceof QueryTable<?> table && model.equals(table.model);
  }

  @Override
  public final int hashCode() {
    return model.hashCode();
  }
}
