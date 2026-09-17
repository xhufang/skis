package io.skis.query;

import java.util.Objects;

/** FROM stage used by {@link Sql#select} description factories. */
public final class SelectDescriptionFromStep<D> {

  private final Factory<D> factory;

  SelectDescriptionFromStep(Factory<D> factory) {
    this.factory = Objects.requireNonNull(factory, "factory");
  }

  /** Chooses an independent entity root for the reusable description. */
  public <F> D from(QueryTable<F> table) {
    return factory.create(Objects.requireNonNull(table, "table"));
  }

  /** Chooses a root-type-neutral derived relation for the reusable description. */
  public D from(DerivedRelation relation) {
    return factory.create(Objects.requireNonNull(relation, "relation"));
  }

  @FunctionalInterface
  interface Factory<D> {

    D create(QueryRelation relation);
  }
}
