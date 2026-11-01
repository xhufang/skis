package io.skis.query.internal;

import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.ExecutionContext;
import io.skis.query.CountQuery;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Built-in count plan derived from an immutable query. */
final class DefaultCountQuery implements CountQuery {

  private final @Nullable DefaultQueryOperations operations;
  private final AbstractSelectQuery<?, ?> source;

  DefaultCountQuery(DefaultQueryOperations operations, DefaultExecutableSelectQuery<?> source) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.source = Objects.requireNonNull(source, "source");
  }

  DefaultCountQuery(DefaultSelectQuery<?> source) {
    this.operations = null;
    this.source = Objects.requireNonNull(source, "source");
  }

  @Nullable DefaultQueryOperations operations() {
    return operations;
  }

  ExecutionContext executionContext() {
    return source instanceof DefaultExecutableSelectQuery<?> executable
        ? executable.executionContext()
        : ExecutionContext.EMPTY;
  }

  QueryCompilation<Long> compilation(
      DefaultQueryOperations current,
      ExecutionContext context,
      @Nullable OperationObservation observation) {
    if (source instanceof DefaultExecutableSelectQuery<?> executable)
      return executable.countCompilation(observation);
    return current.executable(source, context.executionOptions()).countCompilation(observation);
  }
}
