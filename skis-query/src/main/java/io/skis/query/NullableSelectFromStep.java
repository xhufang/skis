package io.skis.query;

/** FROM stage for a selected nullable scalar or complete entity. */
public interface NullableSelectFromStep<S, R> {

  /** Chooses an independent root; the selected target must enter the final join scope. */
  <F> NullableSelectQuery<F, R> from(QueryTable<F> table);
}
