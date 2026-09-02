package io.skis.query;

import java.util.Objects;

/** Immutable FROM stage returned after selecting a scalar or user projection. */
final class DefaultSelectFromStep<E, R> implements SelectFromStep<E, R> {

  private final DefaultQueryOperations operations;
  private final Projection<E, R> projection;

  DefaultSelectFromStep(DefaultQueryOperations operations, Projection<E, R> projection) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.projection = Objects.requireNonNull(projection, "projection");
  }

  @Override
  public SelectQuery<E, R> from(QueryTable<E> table) {
    return operations.selectFrom(projection, Objects.requireNonNull(table, "table"));
  }
}
