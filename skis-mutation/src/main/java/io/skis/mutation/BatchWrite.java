package io.skis.mutation;

import io.skis.core.ExecutionOptions;

/** Ordered collection write; each terminal execution is independent. */
public interface BatchWrite<E> extends ReturningSelection<E> {

  BatchWrite<E> batchMode(BatchMode mode);

  BatchWrite<E> batchOptions(BatchOptions options);

  int write();

  int write(ExecutionOptions options);
}
