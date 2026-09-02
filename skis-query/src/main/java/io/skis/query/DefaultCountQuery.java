package io.skis.query;

import io.skis.core.ExecutionContext;
import java.util.Objects;

/** Built-in count plan derived from an immutable query. */
final class DefaultCountQuery implements CountQuery {

  private final DefaultQueryOperations operations;
  private final DefaultSelectQuery<?, ?> source;

  DefaultCountQuery(DefaultQueryOperations operations, DefaultSelectQuery<?, ?> source) {
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
}
