package io.skis.query;

import java.util.List;
import java.util.Optional;

/** Immutable, reusable single-entity select query. */
public interface EntitySelectQuery<E> {

  /** Returns a new query with its value-bound predicate. */
  EntitySelectQuery<E> where(QueryPredicate predicate);

  /** Executes a query requiring at most one row. */
  Optional<E> fetchOne();

  /** Executes and materializes all rows. */
  List<E> fetchList();

  /** Convenient alias for {@link #fetchList()}. */
  default List<E> fetch() {
    return fetchList();
  }
}
