package io.skis.mutation;

import io.skis.query.QueryCondition;

public interface MergeMatched<E> {

  MergeMatched<E> and(QueryCondition condition);

  MergeUpdateSet<E> thenUpdate();
}
