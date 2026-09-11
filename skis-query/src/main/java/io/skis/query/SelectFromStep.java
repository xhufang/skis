package io.skis.query;

/** FROM stage for a selected non-null scalar or complete entity. */
public interface SelectFromStep<R> {

  /** Chooses an independent root; selected expressions are validated in the final query scope. */
  <F> SelectQuery<F, R> from(QueryTable<F> table);
}
