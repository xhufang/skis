package io.skis.jdbc;

import io.skis.core.ExecutionContext;
import io.skis.core.TransactionException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Non-thread-safe local JDBC transaction owning one acquired connection. */
public final class JdbcTransaction implements AutoCloseable {

  private enum State {
    ACTIVE,
    COMMITTED,
    ROLLED_BACK,
    FAILED,
    CLOSED
  }

  private final ConnectionProvider owner;
  private final ExecutionContext executionContext;
  private final Connection connection;
  private final boolean originalAutoCommit;
  private final JdbcExecutor jdbcExecutor;
  private State state = State.ACTIVE;

  private JdbcTransaction(
      ConnectionProvider owner,
      ExecutionContext executionContext,
      Connection connection,
      boolean originalAutoCommit) {
    this.owner = owner;
    this.executionContext = executionContext;
    this.connection = connection;
    this.originalAutoCommit = originalAutoCommit;
    this.jdbcExecutor = new JdbcExecutor(new TransactionConnectionProvider());
  }

  /** Begins a local transaction with the empty execution context. */
  public static JdbcTransaction begin(ConnectionProvider connectionProvider) {
    return begin(connectionProvider, ExecutionContext.EMPTY);
  }

  /** Acquires one connection and begins a local transaction. */
  public static JdbcTransaction begin(
      ConnectionProvider connectionProvider, ExecutionContext executionContext) {
    ConnectionProvider provider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    ExecutionContext context = Objects.requireNonNull(executionContext, "executionContext");
    if (!provider.supportsLocalTransactions()) {
      throw new TransactionException(
          "connection provider delegates transaction ownership to an external transaction manager");
    }

    Connection connection = null;
    try {
      connection = provider.acquire(context);
      boolean originalAutoCommit = connection.getAutoCommit();
      if (originalAutoCommit) {
        connection.setAutoCommit(false);
      }
      return new JdbcTransaction(provider, context, connection, originalAutoCommit);
    } catch (SQLException failure) {
      if (connection != null) {
        try {
          provider.release(connection, context);
        } catch (SQLException | RuntimeException | Error releaseFailure) {
          failure.addSuppressed(releaseFailure);
        }
      }
      throw new TransactionException("cannot begin JDBC transaction", failure);
    } catch (RuntimeException | Error failure) {
      if (connection != null) {
        try {
          provider.release(connection, context);
        } catch (SQLException | RuntimeException | Error releaseFailure) {
          failure.addSuppressed(releaseFailure);
        }
      }
      throw failure;
    }
  }

  /** Returns the executor whose operations share this transaction connection. */
  public JdbcExecutor jdbcExecutor() {
    requireActive();
    return jdbcExecutor;
  }

  /** Returns whether the transaction still accepts operations and can be completed. */
  public boolean active() {
    return state == State.ACTIVE;
  }

  /** Commits the connection exactly once. */
  public void commit() {
    requireActive();
    try {
      connection.commit();
      state = State.COMMITTED;
    } catch (SQLException failure) {
      state = State.FAILED;
      throw new TransactionException(
          "JDBC commit failed; transaction outcome may be unknown", failure);
    }
  }

  /** Rolls back the connection exactly once. */
  public void rollback() {
    requireActive();
    try {
      connection.rollback();
      state = State.ROLLED_BACK;
    } catch (SQLException failure) {
      state = State.FAILED;
      throw new TransactionException(
          "JDBC rollback failed; transaction outcome may be unknown", failure);
    }
  }

  /** Rolls back an active transaction, restores connection state, and releases ownership. */
  @Override
  public void close() {
    if (state == State.CLOSED) {
      return;
    }
    Throwable pendingFailure = null;
    if (state == State.ACTIVE) {
      try {
        rollback();
      } catch (RuntimeException | Error failure) {
        pendingFailure = failure;
      }
    }
    if (originalAutoCommit && state != State.FAILED) {
      try {
        connection.setAutoCommit(true);
      } catch (SQLException | RuntimeException | Error failure) {
        pendingFailure = combine(pendingFailure, failure);
      }
    }
    try {
      owner.release(connection, executionContext);
    } catch (SQLException | RuntimeException | Error failure) {
      pendingFailure = combine(pendingFailure, failure);
    } finally {
      state = State.CLOSED;
    }
    if (pendingFailure != null) {
      if (pendingFailure instanceof Error error) {
        throw error;
      }
      if (pendingFailure instanceof TransactionException transactionFailure) {
        throw transactionFailure;
      }
      throw new TransactionException("cannot close JDBC transaction", pendingFailure);
    }
  }

  private void requireActive() {
    if (state != State.ACTIVE) {
      throw new TransactionException(
          "transaction is not active [state=" + state.name().toLowerCase() + "]");
    }
  }

  private static Throwable combine(@Nullable Throwable existing, Throwable addition) {
    if (existing == null) {
      return addition;
    }
    existing.addSuppressed(addition);
    return existing;
  }

  private final class TransactionConnectionProvider implements ConnectionProvider {

    @Override
    public boolean supportsLocalTransactions() {
      return false;
    }

    @Override
    public Connection acquire(ExecutionContext context) throws SQLException {
      Objects.requireNonNull(context, "context");
      if (!active()) {
        throw new SQLException("transaction connection is no longer active");
      }
      return connection;
    }

    @Override
    public void release(Connection released, ExecutionContext context) throws SQLException {
      Objects.requireNonNull(released, "connection");
      Objects.requireNonNull(context, "context");
      if (released != connection) {
        throw new SQLException("attempted to release a foreign transaction connection");
      }
    }
  }
}
