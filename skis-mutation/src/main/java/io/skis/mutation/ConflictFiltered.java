package io.skis.mutation;

import io.skis.query.QueryCondition;

public interface ConflictFiltered<E> extends SingleWrite<E> {

  ConflictFiltered<E> and(QueryCondition condition);

  ConflictFiltered<E> or(QueryCondition condition);
}
