package io.skis.mutation;

import io.skis.query.Selectable;

/** Complete INSERT input, optionally followed by one explicit conflict action. */
public interface InsertWrite<E> extends SingleWrite<E> {

  ConflictAction<E> onConflict(Selectable<?> first, Selectable<?>... remaining);
}
