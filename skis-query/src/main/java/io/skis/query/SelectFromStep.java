package io.skis.query;

/** FROM stage for a selected non-null scalar. */
public interface SelectFromStep<E, R> {

  /** Selects the single table expression that owns every projection column. */
  SelectQuery<E, R> from(QueryTable<E> table);
}
