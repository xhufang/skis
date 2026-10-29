package io.skis.mutation;

import io.skis.query.*;

public interface ConflictUpdate<E> extends ConflictSet<E>, SingleWrite<E> {

  ConflictFiltered<E> where(QueryCondition condition);

  ConflictUpdate<E> expectedVersion(Number value);

  ConflictUpdate<E> expectedVersionExpression(NonNullSelectable<? extends Number> expression);
}
