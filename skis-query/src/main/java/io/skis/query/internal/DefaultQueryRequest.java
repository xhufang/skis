package io.skis.query.internal;

import io.skis.core.ExecutionOptions;
import io.skis.query.QueryRequest;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** A terminal and its immutable input; no executor, resources, or previous result are retained. */
final class DefaultQueryRequest<R extends @Nullable Object, T extends @Nullable Object>
    implements QueryRequest<T> {

  private final DefaultSelectQuery<R> query;
  private final Function<DefaultExecutableSelectQuery<R>, T> terminal;
  private final ExecutionOptions options;

  DefaultQueryRequest(
      DefaultSelectQuery<R> query,
      Function<DefaultExecutableSelectQuery<R>, T> terminal,
      ExecutionOptions options) {
    this.query = Objects.requireNonNull(query, "query");
    this.terminal = Objects.requireNonNull(terminal, "terminal");
    this.options = Objects.requireNonNull(options, "options");
  }

  @Override
  public QueryRequest<T> withOptions(ExecutionOptions options) {
    Objects.requireNonNull(options, "options");
    return this.options.equals(options)
        ? this
        : new DefaultQueryRequest<>(query, terminal, options);
  }

  T run(DefaultQueryOperations operations) {
    return terminal.apply(operations.executable(query, options));
  }
}
