package io.skis.query;

/** Required FROM stage that exposes no terminal query operation. */
public interface SelectFromStep<Q> {

  /** Chooses an independent entity-table root. */
  Q from(QueryTable<?> table);

  /** Chooses a derived root. */
  Q from(DerivedRelation relation);
}
