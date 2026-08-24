package io.skis.query;

import java.util.Objects;

/** Opaque immutable predicate built by generated query columns. */
public final class QueryPredicate {

  private final QueryColumn<?, ?> column;
  private final Object value;

  private QueryPredicate(QueryColumn<?, ?> column, Object value) {
    this.column = Objects.requireNonNull(column, "column");
    this.value = Objects.requireNonNull(value, "value");
  }

  static QueryPredicate equal(QueryColumn<?, ?> column, Object value) {
    return new QueryPredicate(column, value);
  }

  QueryColumn<?, ?> column() {
    return column;
  }

  Object value() {
    return value;
  }
}
