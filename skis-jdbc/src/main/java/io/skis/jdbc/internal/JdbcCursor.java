package io.skis.jdbc.internal;

import org.jspecify.annotations.Nullable;

/**
 * Explicit non-thread-safe JDBC cursor used by higher-level query adapters; {@code R} carries row
 * value nullability.
 */
public interface JdbcCursor<R extends @Nullable Object> extends AutoCloseable {

  boolean advance();

  R current();

  boolean isClosed();

  @Override
  void close();
}
