package io.skis.query;

import java.util.List;
import java.util.Optional;

/** Immutable, reusable single-table projection query. */
public interface ProjectedSelectQuery<E, R> {

  /** Returns a new query with its value-bound predicate. */
  ProjectedSelectQuery<E, R> where(QueryPredicate<E> predicate);

  /** Executes a projection query requiring at most one row. */
  Optional<R> fetchOne();

  /** Executes and materializes all projected rows. */
  List<R> fetchList();

  /** Convenient alias for {@link #fetchList()}. */
  default List<R> fetch() {
    return fetchList();
  }
}
