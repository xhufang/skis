package io.skis.mutation;

import io.skis.core.ExecutionOptions;
import org.jspecify.annotations.Nullable;

/** Terminal stage of an explicitly selected entity or projection return shape. */
public interface ReturningWrite<R extends @Nullable Object> {

  Written<R> write();

  Written<R> write(ExecutionOptions options);
}
