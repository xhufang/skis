package io.skis.query;

import java.util.Objects;

/** Immutable FROM stage returned after selecting a nullable scalar. */
final class DefaultNullableSelectFromStep<E, V> implements NullableSelectFromStep<E, V> {

  private final DefaultQueryOperations operations;
  private final Projection<E, V> projection;

  DefaultNullableSelectFromStep(DefaultQueryOperations operations, Projection<E, V> projection) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.projection = Objects.requireNonNull(projection, "projection");
  }

  @Override
  public NullableScalarQuery<E, V> from(QueryTable<E> table) {
    DefaultSelectQuery<E, V> query =
        operations.selectFrom(projection, Objects.requireNonNull(table, "table"));
    return new DefaultNullableScalarQuery<>(operations, query);
  }
}
