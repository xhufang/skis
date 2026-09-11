package io.skis.query;

/** FROM stage for a selected nullable scalar or complete entity. */
public interface NullableSelectFromStep<R> {

  /** Chooses an independent root; selected expressions are validated in the final query scope. */
  <F> NullableSelectQuery<F, R> from(QueryTable<F> table);
}
