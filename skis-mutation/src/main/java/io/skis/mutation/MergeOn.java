package io.skis.mutation;

import io.skis.query.QueryCondition;

public interface MergeOn<E> {

  MergeActions<E> on(QueryCondition condition);
}
