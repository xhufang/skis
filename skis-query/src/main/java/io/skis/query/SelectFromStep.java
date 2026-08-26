package io.skis.query;

/** FROM stage shared by scalar and user-defined single-table projections. */
public interface SelectFromStep<E, R> {

  /** Selects the single table expression that owns every projection column. */
  ProjectedSelectQuery<E, R> from(QueryTable<E> table);
}
