package io.skis.mutation;

import java.util.Collection;

/** Selects the input of an explicit entity insertion without accessing JDBC. */
public interface InsertInto<E> {

  InsertWrite<E> value(E entity);

  BatchInsertWrite<E> values(Collection<? extends E> entities);
}
