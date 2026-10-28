package io.skis.mutation;

import io.skis.core.ExecutionOptions;

/** Terminal stage of an explicitly selected entity or projection return shape. */
public interface ReturningWrite<R> {

  Written<R> write();

  Written<R> write(ExecutionOptions options);
}
