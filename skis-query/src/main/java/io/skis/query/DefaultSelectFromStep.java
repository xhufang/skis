package io.skis.query;

import java.util.Objects;

/** Immutable FROM stage returned after selecting an entity, scalar, or user projection. */
final class DefaultSelectFromStep<R> implements SelectFromStep<R> {

  private final DefaultQueryOperations operations;
  private final SelectedResult<R> selected;

  DefaultSelectFromStep(DefaultQueryOperations operations, SelectedResult<R> selected) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.selected = Objects.requireNonNull(selected, "selected");
  }

  @Override
  public <F> SelectQuery<F, R> from(QueryTable<F> table) {
    return operations.selectFrom(selected, Objects.requireNonNull(table, "table"));
  }
}
