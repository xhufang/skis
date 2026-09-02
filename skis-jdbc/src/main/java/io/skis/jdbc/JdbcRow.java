package io.skis.jdbc;

import org.jspecify.annotations.Nullable;

/** JDBC-layer row-presence value that permits a nullable decoded value. */
public record JdbcRow<R>(boolean present, @Nullable R value) {

  public JdbcRow {
    if (!present && value != null) {
      throw new IllegalArgumentException("an absent JDBC row cannot contain a value");
    }
  }

  public static <R> JdbcRow<R> absent() {
    return new JdbcRow<>(false, null);
  }

  public static <R> JdbcRow<R> present(@Nullable R value) {
    return new JdbcRow<R>(true, value);
  }
}
