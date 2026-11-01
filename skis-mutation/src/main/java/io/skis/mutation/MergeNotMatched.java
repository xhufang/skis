package io.skis.mutation;

import io.skis.mutation.internal.StructuredWrites;
import io.skis.query.QueryCondition;
import java.util.function.Consumer;

/** Captures the independent INSERT columns and expressions of a MERGE branch. */
public interface MergeNotMatched<E> {

  MergeNotMatched<E> and(QueryCondition condition);

  default MergeWrite<E> thenInsert(Consumer<ExpressionRow> values) {
    return StructuredWrites.mergeInsert(this, values);
  }
}
