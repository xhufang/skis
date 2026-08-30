package io.skis.query;

import io.skis.core.ExecutionOptions;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, reusable single-entity select query. */
public interface EntitySelectQuery<E> {

  /** Returns a new query with its value-bound predicate. */
  EntitySelectQuery<E> where(QueryPredicate<E> predicate);

  /**
   * Returns a new query with immutable per-statement overrides.
   *
   * <p>Built-in queries keep SQL structure and execution options separate, so this does not alter
   * or recompile the query plan.
   */
  default EntitySelectQuery<E> withOptions(ExecutionOptions executionOptions) {
    ExecutionOptions options = Objects.requireNonNull(executionOptions, "executionOptions");
    if (!options.isEmpty()) {
      throw new UnsupportedOperationException(
          "this EntitySelectQuery implementation does not support execution options");
    }
    return this;
  }

  /** Executes a query requiring at most one row. */
  Optional<E> fetchOne();

  /** Executes and materializes all rows. */
  List<E> fetchList();

  /** Convenient alias for {@link #fetchList()}. */
  default List<E> fetch() {
    return fetchList();
  }
}
