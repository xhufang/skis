package io.skis.mutation;

import io.skis.mutation.internal.StructuredWrites;
import io.skis.query.Selectable;
import java.util.Collection;
import java.util.function.Consumer;

/** Selects an entity or explicitly bound row without accessing JDBC. */
public interface InsertInto<E> {

  InsertWrite<E> value(E entity);

  BatchInsertWrite<E> values(Collection<? extends E> entities);

  default InsertSelect<E> columns(Selectable<?> first, Selectable<?>... remaining) {
    return StructuredWrites.selectColumns(this, first, remaining);
  }

  /** Captures one non-empty row; the collector is valid only during the callback. */
  default InsertWrite<E> values(Consumer<ValueRow> values) {
    return StructuredWrites.insert(this, values);
  }
}
