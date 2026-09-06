package io.skis.query;

import java.util.Objects;

/** Immutable FROM stage returned after selecting a nullable scalar or complete entity. */
final class DefaultNullableSelectFromStep<S, R> implements NullableSelectFromStep<S, R> {

  private final DefaultQueryOperations operations;
  private final SelectedResult<S, R> selected;

  DefaultNullableSelectFromStep(DefaultQueryOperations operations, SelectedResult<S, R> selected) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.selected = Objects.requireNonNull(selected, "selected");
  }

  @Override
  public <F> NullableSelectQuery<F, R> from(QueryTable<F> table) {
    DefaultSelectQuery<F, R> query =
        operations.selectFrom(selected, Objects.requireNonNull(table, "table"));
    return new DefaultNullableSelectQuery<>(operations, query);
  }
}
