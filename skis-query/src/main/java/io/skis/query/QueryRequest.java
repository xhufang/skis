package io.skis.query;

import io.skis.core.ExecutionOptions;
import org.jspecify.annotations.Nullable;

/** Immutable, resource-free execution request; T is the complete terminal result type. */
public interface QueryRequest<T extends @Nullable Object> {

  /** Replaces execution overrides without changing the query or copying its captured values. */
  QueryRequest<T> withOptions(ExecutionOptions options);
}
