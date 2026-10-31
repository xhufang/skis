package io.skis.mutation;

import io.skis.query.QueryCondition;

public interface MergeActions<E> {

  MergeMatched<E> whenMatched();

  MergeNotMatched<E> whenNotMatched();

  default MergeMatched<E> whenMatched(QueryCondition condition) {
    return whenMatched().and(condition);
  }

  default MergeNotMatched<E> whenNotMatched(QueryCondition condition) {
    return whenNotMatched().and(condition);
  }
}
