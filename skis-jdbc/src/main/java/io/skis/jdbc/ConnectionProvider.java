package io.skis.jdbc;

import io.skis.core.ExecutionContext;
import java.sql.Connection;
import java.sql.SQLException;

/** Acquires and releases JDBC connections for one execution. */
public interface ConnectionProvider {

  /**
   * Acquires a connection for the supplied execution context.
   *
   * @throws SQLException when a connection cannot be acquired
   */
  Connection acquire(ExecutionContext context) throws SQLException;

  /**
   * Releases a connection previously returned by {@link #acquire(ExecutionContext)}.
   *
   * <p>Callers must invoke this method from a {@code finally} block. A provider may close the
   * connection, return it to a pool, or detach it from a transaction according to its ownership
   * model. If execution and release both fail, the release failure must be attached to the
   * execution failure as a suppressed exception instead of replacing it.
   *
   * @throws SQLException when the connection cannot be released
   */
  void release(Connection connection, ExecutionContext context) throws SQLException;
}
