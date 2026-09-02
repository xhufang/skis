package io.skis.query;

import org.jspecify.annotations.Nullable;

/** Non-thread-safe, explicit JDBC-backed row cursor. */
public interface QueryCursor<R extends @Nullable Object> extends AutoCloseable {

  boolean advance();

  R current();

  boolean isClosed();

  @Override
  void close();
}
