package io.skis.query;

import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.ExecutionContext;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Built-in count plan derived from an immutable query. */
final class DefaultCountQuery implements CountQuery {

  private final DefaultQueryOperations operations;
  private final DefaultSelectQuery<?> source;

  DefaultCountQuery(DefaultQueryOperations operations, DefaultSelectQuery<?> source) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.source = Objects.requireNonNull(source, "source");
  }

  DefaultQueryOperations operations() {
    return operations;
  }

  ExecutionContext executionContext() {
    return source.executionContext();
  }

  QueryCompilation<Long> compilation() {
    return source.countCompilation();
  }

  QueryCompilation<Long> compilation(@Nullable OperationObservation observation) {
    return source.countCompilation(observation);
  }
}
