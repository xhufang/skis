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
    ACTIVE("active"),
    COMMITTED("committed"),
    ROLLED_BACK("rolled-back"),
    COMMIT_OUTCOME_UNKNOWN("commit-outcome-unknown"),
    ROLLBACK_OUTCOME_UNKNOWN("rollback-outcome-unknown"),
    CLOSED("closed");

    private final String description;

    State(String description) {
      this.description = description;
    }
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
      boolean originalAutoCommit,
      JdbcExecutor executorTemplate) {
    this.owner = owner;
    this.executionContext = executionContext;
    this.connection = connection;
    this.originalAutoCommit = originalAutoCommit;
    this.jdbcExecutor =
        Objects.requireNonNull(executorTemplate, "executorTemplate")
            .withConnectionProvider(new TransactionConnectionProvider());
  }

  /** Begins a local transaction with the empty execution context. */
  public static JdbcTransaction begin(ConnectionProvider connectionProvider) {
    ConnectionProvider provider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    return beginInternal(provider, ExecutionContext.EMPTY, new JdbcExecutor(provider));
  }

  /** Acquires one connection and begins a local transaction. */
  public static JdbcTransaction begin(
      ConnectionProvider connectionProvider, ExecutionContext executionContext) {
    ConnectionProvider provider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    return beginInternal(provider, executionContext, new JdbcExecutor(provider));
  }

  /** Acquires one connection from an executor while retaining all of its immutable settings. */
  public static JdbcTransaction beginWithExecutor(
      JdbcExecutor executorTemplate, ExecutionContext executionContext) {
    JdbcExecutor template = Objects.requireNonNull(executorTemplate, "executorTemplate");
    return beginInternal(template.connectionProvider(), executionContext, template);
  }

  private static JdbcTransaction beginInternal(
      ConnectionProvider provider,
      ExecutionContext executionContext,
      JdbcExecutor executorTemplate) {
    ExecutionContext context = Objects.requireNonNull(executionContext, "executionContext");
    JdbcExecutor template = Objects.requireNonNull(executorTemplate, "executorTemplate");
    if (!provider.supportsLocalTransactions()) {
      throw new TransactionException(
          "connection provider delegates transaction ownership to an external transaction manager");
    }

    Connection connection = null;
    Boolean acquiredAutoCommit = null;
    boolean autoCommitChangeAttempted = false;
    try {
      connection = provider.acquire(context);
      boolean originalAutoCommit = connection.getAutoCommit();
      acquiredAutoCommit = originalAutoCommit;
      if (originalAutoCommit) {
        autoCommitChangeAttempted = true;
        connection.setAutoCommit(false);
      }
      return new JdbcTransaction(provider, context, connection, originalAutoCommit, template);
    } catch (SQLException failure) {
      cleanUpFailedBegin(
          provider, context, connection, acquiredAutoCommit, autoCommitChangeAttempted, failure);
      throw new TransactionException("cannot begin JDBC transaction", failure);
    } catch (RuntimeException | Error failure) {
      cleanUpFailedBegin(
          provider, context, connection, acquiredAutoCommit, autoCommitChangeAttempted, failure);
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
    } catch (SQLException | RuntimeException failure) {
      state = State.COMMIT_OUTCOME_UNKNOWN;
      throw new TransactionException(
          "JDBC commit failed; transaction outcome may be unknown", failure);
    } catch (Error failure) {
      state = State.COMMIT_OUTCOME_UNKNOWN;
      throw failure;
    }
  }

  /** Rolls back the connection exactly once. */
  public void rollback() {
    requireActive();
    try {
      connection.rollback();
      state = State.ROLLED_BACK;
    } catch (SQLException | RuntimeException failure) {
      state = State.ROLLBACK_OUTCOME_UNKNOWN;
      throw new TransactionException(
          "JDBC rollback failed; transaction outcome may be unknown", failure);
    } catch (Error failure) {
      state = State.ROLLBACK_OUTCOME_UNKNOWN;
      throw failure;
    }
  }

  /**
   * Rolls back an active transaction, safely restores connection state, and releases ownership.
   *
   * <p>Auto-commit is restored only after a known successful commit or rollback. It is not changed
   * after an unknown completion outcome because enabling auto-commit could itself commit pending
   * work on some drivers.
   */
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
    if (originalAutoCommit && completionOutcomeIsKnown()) {
      try {
        connection.setAutoCommit(true);
      } catch (SQLException | RuntimeException | Error failure) {
        pendingFailure =
            combine(
                pendingFailure,
                closeFailure(
                    "JDBC transaction "
                        + state.description
                        + " but the original auto-commit state could not be restored",
                    failure));
      }
    }
    try {
      owner.release(connection, executionContext);
    } catch (SQLException | RuntimeException | Error failure) {
      pendingFailure = combine(pendingFailure, closeFailure(releaseFailureMessage(), failure));
    } finally {
      state = State.CLOSED;
    }
    rethrow(pendingFailure);
  }

  private void requireActive() {
    if (state != State.ACTIVE) {
      throw new TransactionException("transaction is not active [state=" + state.description + "]");
    }
  }

  private boolean completionOutcomeIsKnown() {
    return state == State.COMMITTED || state == State.ROLLED_BACK;
  }

  private String releaseFailureMessage() {
    return switch (state) {
      case COMMITTED -> "JDBC transaction committed but its connection could not be released";
      case ROLLED_BACK -> "JDBC transaction rolled back but its connection could not be released";
      case COMMIT_OUTCOME_UNKNOWN ->
          "JDBC commit outcome is unknown and its connection could not be released";
      case ROLLBACK_OUTCOME_UNKNOWN ->
          "JDBC rollback outcome is unknown and its connection could not be released";
      case ACTIVE -> "active JDBC transaction connection could not be released";
      case CLOSED -> "closed JDBC transaction connection could not be released";
    };
  }

  private static void cleanUpFailedBegin(
      ConnectionProvider provider,
      ExecutionContext context,
      @Nullable Connection connection,
      @Nullable Boolean originalAutoCommit,
      boolean autoCommitChangeAttempted,
      Throwable failure) {
    if (connection == null) {
      return;
    }
    if (Boolean.TRUE.equals(originalAutoCommit) && autoCommitChangeAttempted) {
      try {
        connection.setAutoCommit(true);
      } catch (SQLException | RuntimeException | Error restorationFailure) {
        failure.addSuppressed(
            closeFailure(
                "cannot restore auto-commit after JDBC transaction begin failed",
                restorationFailure));
      }
    }
    try {
      provider.release(connection, context);
    } catch (SQLException | RuntimeException | Error releaseFailure) {
      failure.addSuppressed(
          closeFailure(
              "cannot release JDBC connection after transaction begin failed", releaseFailure));
    }
  }

  private static Throwable closeFailure(String message, Throwable failure) {
    if (failure instanceof Error) {
      return failure;
    }
    return new TransactionException(message, failure);
  }

  private static void rethrow(@Nullable Throwable failure) {
    if (failure instanceof Error error) {
      throw error;
    }
    if (failure != null) {
      throw (RuntimeException) failure;
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
