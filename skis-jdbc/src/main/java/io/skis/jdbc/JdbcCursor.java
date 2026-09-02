package io.skis.jdbc;

import org.jspecify.annotations.Nullable;

/** Explicit non-thread-safe JDBC cursor used by higher-level query adapters. */
public interface JdbcCursor<R> extends AutoCloseable {

  boolean advance();

  @Nullable R current();

  boolean isClosed();

  @Override
  void close();
}
