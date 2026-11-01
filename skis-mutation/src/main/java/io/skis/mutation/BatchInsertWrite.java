package io.skis.mutation;

import io.skis.query.Selectable;

/** Ordered entity INSERT inputs; result rows do not imply input-order key correspondence. */
public interface BatchInsertWrite<E> extends BatchWrite<E> {

  @Override
  BatchInsertWrite<E> batchMode(BatchMode mode);

  @Override
  BatchInsertWrite<E> batchOptions(BatchOptions options);

  ConflictAction<E> onConflict(Selectable<?> first, Selectable<?>... remaining);
}
