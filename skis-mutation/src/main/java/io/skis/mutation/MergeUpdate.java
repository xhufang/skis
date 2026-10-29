package io.skis.mutation;

import io.skis.query.NonNullSelectable;

public interface MergeUpdate<E> extends MergeUpdateSet<E>, MergeActions<E>, MergeWrite<E> {

  MergeUpdate<E> expectedVersion(Number value);

  MergeUpdate<E> expectedVersionExpression(NonNullSelectable<? extends Number> expression);
}
