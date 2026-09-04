package io.skis.query;

import java.util.Objects;

/** Immutable FROM stage returned after selecting a nullable scalar. */
final class DefaultNullableSelectFromStep<E, V> implements NullableSelectFromStep<E, V> {

  private final DefaultQueryOperations operations;
  private final SelectedResult<E, V> selected;

  DefaultNullableSelectFromStep(DefaultQueryOperations operations, SelectedResult<E, V> selected) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.selected = Objects.requireNonNull(selected, "selected");
  }

  @Override
  public NullableScalarQuery<E, V> from(QueryTable<E> table) {
    DefaultSelectQuery<E, V> query =
        operations.selectFrom(selected, Objects.requireNonNull(table, "table"));
    return new DefaultNullableScalarQuery<>(operations, query);
  }
}
