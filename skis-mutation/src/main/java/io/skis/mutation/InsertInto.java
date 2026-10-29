package io.skis.mutation;

import io.skis.mutation.internal.StructuredWrites;
import java.util.Collection;
import java.util.function.Consumer;

/** Selects an entity or explicitly bound row without accessing JDBC. */
public interface InsertInto<E> {

  InsertWrite<E> value(E entity);

  BatchInsertWrite<E> values(Collection<? extends E> entities);

  /** Captures one non-empty row; the collector is valid only during the callback. */
  default InsertWrite<E> values(Consumer<ValueRow> values) {
    return StructuredWrites.insert(this, values);
  }
}
