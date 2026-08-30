package io.skis.query;

import io.skis.core.ExecutionOptions;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, reusable single-table projection query. */
public interface ProjectedSelectQuery<E, R> {

  /** Returns a new query with its value-bound predicate. */
  ProjectedSelectQuery<E, R> where(QueryPredicate<E> predicate);

  /** Returns a new query with execution-only options that do not alter its compiled plan. */
  default ProjectedSelectQuery<E, R> withOptions(ExecutionOptions executionOptions) {
    ExecutionOptions options = Objects.requireNonNull(executionOptions, "executionOptions");
    if (!options.isEmpty()) {
      throw new UnsupportedOperationException(
          "this ProjectedSelectQuery implementation does not support execution options");
    }
    return this;
  }

  /** Executes a projection query requiring at most one row. */
  Optional<R> fetchOne();

  /** Executes and materializes all projected rows. */
  List<R> fetchList();

  /** Convenient alias for {@link #fetchList()}. */
  default List<R> fetch() {
    return fetchList();
  }
}
