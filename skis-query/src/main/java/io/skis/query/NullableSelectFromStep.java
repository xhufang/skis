package io.skis.query;

/** FROM stage for a selected nullable scalar. */
public interface NullableSelectFromStep<E, V> {

  /** Selects the table expression that owns the nullable scalar. */
  NullableScalarQuery<E, V> from(QueryTable<E> table);
}
