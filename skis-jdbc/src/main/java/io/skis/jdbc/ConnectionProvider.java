package io.skis.jdbc;

import io.skis.core.ExecutionContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Acquires and releases JDBC connections for one execution. */
public interface ConnectionProvider {

  /**
   * Whether SKIS may directly change auto-commit and commit or roll back acquired connections.
   *
   * <p>Providers backed by an external transaction manager must return {@code false}.
   */
  default boolean supportsLocalTransactions() {
    return true;
  }

  /**
   * Acquires a connection for the supplied execution context.
   *
   * @throws SQLException when a connection cannot be acquired
   */
  Connection acquire(ExecutionContext context) throws SQLException;

  /**
   * Applies provider-specific statement constraints after SKIS has bound parameters and applied
   * its built-in execution options, but before execution.
   *
   * <p>{@code queryTimeoutSeconds} is the effective SKIS timeout: {@code -1} means neither the
   * statement nor executor configured a timeout, {@code 0} explicitly requests the JDBC unlimited
   * value, and a positive value is the requested upper bound. Implementations may shorten a
   * positive timeout to honor an externally managed transaction deadline, but must not lengthen
   * it. Implementations must not execute or close the statement.
   *
   * <p>The default implementation preserves the existing provider behavior.
   *
   * @throws SQLException when provider-specific statement configuration fails
   */
  default void configureStatement(
      PreparedStatement statement, ExecutionContext context, int queryTimeoutSeconds)
      throws SQLException {}

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
