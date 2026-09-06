package io.skis.query;

/** FROM stage for a selected non-null scalar or complete entity. */
public interface SelectFromStep<S, R> {

  /** Chooses an independent of root; the selected target must be in the final join scope. */
  <F> SelectQuery<F, R> from(QueryTable<F> table);
}
