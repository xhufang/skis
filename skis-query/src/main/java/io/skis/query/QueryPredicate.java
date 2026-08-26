package io.skis.query;

import java.util.Objects;

/** Opaque immutable predicate built by generated query columns. */
public final class QueryPredicate<E> {

  private final QueryColumn<E, ?> column;
  private final Object value;

  private QueryPredicate(QueryColumn<E, ?> column, Object value) {
    this.column = Objects.requireNonNull(column, "column");
    this.value = Objects.requireNonNull(value, "value");
  }

  static <E> QueryPredicate<E> equal(QueryColumn<E, ?> column, Object value) {
    return new QueryPredicate<>(column, value);
  }

  QueryColumn<E, ?> column() {
    return column;
  }

  Object value() {
    return value;
  }
}
