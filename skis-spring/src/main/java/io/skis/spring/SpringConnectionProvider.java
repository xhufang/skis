package io.skis.spring;

import io.skis.core.spi.ExecutionContext;
import io.skis.jdbc.spi.ConnectionProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Connection provider that reuses Spring transaction-bound DataSource connections.
 *
 * <p>Transaction completion remains owned by Spring. This provider only participates in Spring's
 * connection reference counting and never commits or rolls back an acquired connection.
 */
public final class SpringConnectionProvider implements ConnectionProvider {

  private final DataSource dataSource;
  private final Object cursorScopeKey = new Object();

  /** Creates a provider for the DataSource managed by the surrounding Spring application. */
  public SpringConnectionProvider(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public boolean supportsLocalTransactions() {
    return false;
  }

  @Override
  public Connection acquire(ExecutionContext context) throws SQLException {
    Objects.requireNonNull(context, "context");
    try {
      return DataSourceUtils.getConnection(dataSource);
    } catch (org.springframework.jdbc.CannotGetJdbcConnectionException failure) {
      if (failure.getCause() instanceof SQLException sqlFailure) {
        throw sqlFailure;
      }
      throw new SQLException("Spring could not acquire a DataSource connection", failure);
    }
  }

  @Override
  public void configureStatement(
      PreparedStatement statement, ExecutionContext context, int queryTimeoutSeconds)
      throws SQLException {
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(context, "context");
    if (queryTimeoutSeconds < -1) {
      throw new IllegalArgumentException("queryTimeoutSeconds must be -1, zero, or positive");
    }

    DataSourceUtils.applyTransactionTimeout(statement, dataSource);
    if (queryTimeoutSeconds > 0) {
      int appliedTimeoutSeconds = statement.getQueryTimeout();
      if (appliedTimeoutSeconds <= 0 || appliedTimeoutSeconds > queryTimeoutSeconds) {
        statement.setQueryTimeout(queryTimeoutSeconds);
      }
    }
  }

  @Override
  public void release(Connection connection, ExecutionContext context) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(context, "context");
    DataSourceUtils.doReleaseConnection(connection, dataSource);
  }

  @Override
  public @Nullable Runnable registerCursor(
      Connection connection, AutoCloseable cursor, ExecutionContext context) {
    Objects.requireNonNull(cursor, "cursor");
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || !DataSourceUtils.isConnectionTransactional(connection, dataSource)) {
      return null;
    }
    CursorSynchronization synchronization =
        (CursorSynchronization) TransactionSynchronizationManager.getResource(cursorScopeKey);
    if (synchronization == null) {
      synchronization = new CursorSynchronization(cursorScopeKey);
      TransactionSynchronizationManager.bindResource(cursorScopeKey, synchronization);
      try {
        TransactionSynchronizationManager.registerSynchronization(synchronization);
      } catch (RuntimeException | Error failure) {
        TransactionSynchronizationManager.unbindResource(cursorScopeKey);
        throw failure;
      }
    }
    return synchronization.new CursorRegistration(cursor);
  }

  private static final class CursorSynchronization implements TransactionSynchronization {
    private final Object scopeKey;
    private @Nullable CursorRegistration cursors;
    private long cursorSequence;
    // Spring has no savepoint-release callback; completed nested scopes must not be retained.
    private @Nullable Map<Object, Long> savePoints;
    private @Nullable Throwable savepointCloseFailure;

    private CursorSynchronization(Object scopeKey) {
      this.scopeKey = scopeKey;
    }

    @Override
    public int getOrder() {
      return DataSourceUtils.CONNECTION_SYNCHRONIZATION_ORDER - 1;
    }

    @Override
    public void suspend() {
      TransactionSynchronizationManager.unbindResource(scopeKey);
    }

    @Override
    public void resume() {
      TransactionSynchronizationManager.bindResource(scopeKey, this);
    }

    @Override
    public void savepoint(Object savepoint) {
      if (cursors != null) {
        Map<Object, Long> boundaries = savePoints;
        if (boundaries == null) {
          boundaries = new WeakHashMap<>();
          savePoints = boundaries;
        }
        boundaries.put(savepoint, cursorSequence);
      }
    }

    @Override
    public void savepointRollback(Object savepoint) {
      Map<Object, Long> boundaries = savePoints;
      Long boundary = boundaries == null ? null : boundaries.get(savepoint);
      // An unseen savepoint predates the first cursor in this scope.
      Throwable failure = closeCursorsAfter(boundary == null ? 0L : boundary);
      if (failure != null) {
        // Throwing here would prevent Spring from issuing the JDBC savepoint rollback.
        if (savepointCloseFailure == null) {
          // beforeCommit must not throw a Spring TransactionException: Spring can treat that
          // as an actual commit failure rather than rolling the transaction back.
          savepointCloseFailure =
              failure instanceof Error
                  ? failure
                  : new IllegalStateException("cannot close savepoint cursor", failure);
        } else if (savepointCloseFailure != failure) {
          savepointCloseFailure.addSuppressed(failure);
        }
      }
    }

    @Override
    public void beforeCommit(boolean readOnly) {
      closeCursors();
    }

    @Override
    public void beforeCompletion() {
      closeCursors();
    }

    @Override
    public void afterCompletion(int status) {
      try {
        closeCursors();
      } finally {
        savePoints = null;
        if (TransactionSynchronizationManager.getResource(scopeKey) == this) {
          TransactionSynchronizationManager.unbindResource(scopeKey);
        }
      }
    }

    private void closeCursors() {
      Throwable failure = savepointCloseFailure;
      savepointCloseFailure = null;
      Throwable closeFailure = closeCursorsAfter(0L);
      if (failure == null) failure = closeFailure;
      else if (closeFailure != null && failure != closeFailure) failure.addSuppressed(closeFailure);
      if (failure instanceof Error error) throw error;
      if (failure instanceof RuntimeException runtime) throw runtime;
      if (failure != null)
        throw new IllegalStateException("cannot close transaction cursor", failure);
    }

    private @Nullable Throwable closeCursorsAfter(long sequence) {
      Throwable failure = null;
      CursorRegistration registration;
      while ((registration = cursors) != null && registration.sequence > sequence) {
        AutoCloseable resource = Objects.requireNonNull(registration.cursor);
        registration.run();
        try {
          resource.close();
        } catch (Exception | Error closeFailure) {
          if (failure == null) failure = closeFailure;
          else if (failure != closeFailure) failure.addSuppressed(closeFailure);
        }
      }
      return failure;
    }

    /** Removal uses the registration itself, preserving reverse-open order without a search. */
    private final class CursorRegistration implements Runnable {
      private final long sequence;
      private @Nullable AutoCloseable cursor;
      private @Nullable CursorRegistration previous;
      private @Nullable CursorRegistration next;

      private CursorRegistration(AutoCloseable cursor) {
        this.sequence = ++cursorSequence;
        this.cursor = cursor;
        this.next = cursors;
        if (next != null) {
          next.previous = this;
        }
        cursors = this;
      }

      @Override
      public void run() {
        if (cursor == null) {
          return;
        }
        CursorRegistration before = previous;
        CursorRegistration after = next;
        if (before == null) {
          cursors = after;
        } else {
          before.next = after;
        }
        if (after != null) {
          after.previous = before;
        }
        cursor = null;
        previous = null;
        next = null;
      }
    }
  }
}
