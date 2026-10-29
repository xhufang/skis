package io.skis.mutation;

import io.skis.mutation.internal.StructuredWrites;
import java.util.function.Consumer;

/** Captures the independent INSERT columns and expressions of a MERGE branch. */
public interface MergeNotMatched<E> {

  default MergeWrite<E> thenInsert(Consumer<ExpressionRow> values) {
    return StructuredWrites.mergeInsert(this, values);
  }
}
