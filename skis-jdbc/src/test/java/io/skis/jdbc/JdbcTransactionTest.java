package io.skis.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.ExecutionContext;
import io.skis.core.TransactionException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class JdbcTransactionTest {

  @Test
  void commitsThenRestoresAndReleasesTheOwnedConnection() {
    Scenario scenario = new Scenario(true);

    try (JdbcTransaction transaction = JdbcTransaction.begin(scenario.provider())) {
      assertFalse(scenario.autoCommit.get());
      transaction.commit();
    }

    assertEquals(1, scenario.commits.get());
    assertEquals(0, scenario.rollbacks.get());
    assertEquals(1, scenario.releases.get());
    assertEquals(2, scenario.autoCommitChanges.get());
    assertTrue(scenario.autoCommit.get());
    assertEquals(
        List.of("auto-commit:false", "commit", "auto-commit:true", "release"),
        scenario.events);
  }

  @Test
  void preservesAnExistingManualCommitMode() {
    Scenario scenario = new Scenario(false);

    try (JdbcTransaction transaction = JdbcTransaction.begin(scenario.provider())) {
      transaction.commit();
    }

    assertEquals(1, scenario.commits.get());
    assertEquals(0, scenario.autoCommitChanges.get());
    assertFalse(scenario.autoCommit.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void closingAnActiveTransactionRollsItBack() {
    Scenario scenario = new Scenario(true);

    try (JdbcTransaction ignored = JdbcTransaction.begin(scenario.provider())) {
      // Closing an uncompleted transaction is the rollback boundary.
    }

    assertEquals(0, scenario.commits.get());
    assertEquals(1, scenario.rollbacks.get());
    assertEquals(1, scenario.releases.get());
    assertTrue(scenario.autoCommit.get());
    assertEquals(
        List.of("auto-commit:false", "rollback", "auto-commit:true", "release"),
        scenario.events);
  }

  @Test
  void closeIsIdempotentAndReleasesOnlyOnce() {
    Scenario scenario = new Scenario(true);
    JdbcTransaction transaction = JdbcTransaction.begin(scenario.provider());

    transaction.rollback();
    transaction.close();
    transaction.close();

    assertEquals(1, scenario.rollbacks.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void commitFailureLeavesTheOutcomeUnknownAndDoesNotAttemptRollbackOrStateRestoration() {
    Scenario scenario = new Scenario(true).fail(FailurePoint.COMMIT);
    JdbcTransaction transaction = JdbcTransaction.begin(scenario.provider());

    TransactionException failure = assertThrows(TransactionException.class, transaction::commit);

    assertSame(scenario.failure(FailurePoint.COMMIT), failure.getCause());
    assertTrue(failure.getMessage().contains("outcome may be unknown"));
    assertFalse(transaction.active());
    transaction.close();

    assertEquals(1, scenario.commits.get());
    assertEquals(0, scenario.rollbacks.get());
    assertEquals(1, scenario.autoCommitChanges.get());
    assertFalse(scenario.autoCommit.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void rollbackFailureLeavesTheOutcomeUnknownAndSkipsStateRestoration() {
    Scenario scenario = new Scenario(true).fail(FailurePoint.ROLLBACK);
    JdbcTransaction transaction = JdbcTransaction.begin(scenario.provider());

    TransactionException failure = assertThrows(TransactionException.class, transaction::rollback);

    assertSame(scenario.failure(FailurePoint.ROLLBACK), failure.getCause());
    assertFalse(transaction.active());
    transaction.close();

    assertEquals(1, scenario.rollbacks.get());
    assertEquals(1, scenario.autoCommitChanges.get());
    assertFalse(scenario.autoCommit.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void releaseFailureAfterUnknownCommitKeepsTheUnknownOutcomeVisible() {
    Scenario scenario = new Scenario(true).fail(FailurePoint.COMMIT).fail(FailurePoint.RELEASE);
    JdbcTransaction transaction = JdbcTransaction.begin(scenario.provider());
    assertThrows(TransactionException.class, transaction::commit);

    TransactionException failure = assertThrows(TransactionException.class, transaction::close);

    assertTrue(failure.getMessage().contains("commit outcome is unknown"));
    assertEquals(0, scenario.rollbacks.get());
    assertEquals(1, scenario.autoCommitChanges.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void closePreservesRollbackFailureAndSuppressesReleaseFailure() {
    Scenario scenario =
        new Scenario(true).fail(FailurePoint.ROLLBACK).fail(FailurePoint.RELEASE);
    JdbcTransaction transaction = JdbcTransaction.begin(scenario.provider());

    TransactionException failure = assertThrows(TransactionException.class, transaction::close);

    assertTrue(failure.getMessage().contains("rollback failed"));
    assertEquals(1, failure.getSuppressed().length);
    assertTrue(
        failure.getSuppressed()[0].getMessage().contains("connection could not be released"));
    assertEquals(1, scenario.releases.get());
    assertFalse(transaction.active());
  }

  @Test
  void closeReportsCommittedOutcomeWhenRestorationAndReleaseFail() {
    Scenario scenario =
        new Scenario(true)
            .fail(FailurePoint.RESTORE_AUTO_COMMIT)
            .fail(FailurePoint.RELEASE);
    JdbcTransaction transaction = JdbcTransaction.begin(scenario.provider());
    transaction.commit();

    TransactionException failure = assertThrows(TransactionException.class, transaction::close);

    assertTrue(failure.getMessage().contains("committed"));
    assertTrue(failure.getMessage().contains("auto-commit"));
    assertEquals(1, failure.getSuppressed().length);
    assertTrue(
        failure.getSuppressed()[0].getMessage().contains("connection could not be released"));
    assertEquals(1, scenario.commits.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void beginFailureAttemptsSafeRestorationAndReleaseAndPreservesEveryFailure() {
    Scenario scenario =
        new Scenario(true)
            .fail(FailurePoint.DISABLE_AUTO_COMMIT)
            .fail(FailurePoint.RESTORE_AUTO_COMMIT)
            .fail(FailurePoint.RELEASE);

    TransactionException failure =
        assertThrows(TransactionException.class, () -> JdbcTransaction.begin(scenario.provider()));

    assertSame(scenario.failure(FailurePoint.DISABLE_AUTO_COMMIT), failure.getCause());
    assertEquals(2, failure.getCause().getSuppressed().length);
    assertTrue(failure.getCause().getSuppressed()[0].getMessage().contains("restore auto-commit"));
    assertTrue(
        failure
            .getCause()
            .getSuppressed()[1]
            .getMessage()
            .contains("release JDBC connection"));
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void autoCommitInspectionFailureStillReleasesAndPreservesReleaseFailure() {
    Scenario scenario =
        new Scenario(true).fail(FailurePoint.GET_AUTO_COMMIT).fail(FailurePoint.RELEASE);

    TransactionException failure =
        assertThrows(TransactionException.class, () -> JdbcTransaction.begin(scenario.provider()));

    assertSame(scenario.failure(FailurePoint.GET_AUTO_COMMIT), failure.getCause());
    assertEquals(1, failure.getCause().getSuppressed().length);
    assertTrue(
        failure.getCause().getSuppressed()[0].getMessage().contains("release JDBC connection"));
    assertEquals(0, scenario.autoCommitChanges.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void rejectsExternallyManagedProvidersBeforeAcquiringAConnection() {
    AtomicInteger acquisitions = new AtomicInteger();
    ConnectionProvider provider =
        new ConnectionProvider() {
          @Override
          public boolean supportsLocalTransactions() {
            return false;
          }

          @Override
          public Connection acquire(@NonNull ExecutionContext context) {
            acquisitions.incrementAndGet();
            return null;
          }

          @Override
          public void release(Connection connection, ExecutionContext context) {}
        };

    assertThrows(TransactionException.class, () -> JdbcTransaction.begin(provider));
    assertEquals(0, acquisitions.get());
  }

  private enum FailurePoint {
    GET_AUTO_COMMIT,
    DISABLE_AUTO_COMMIT,
    RESTORE_AUTO_COMMIT,
    COMMIT,
    ROLLBACK,
    RELEASE
  }

  private static final class Scenario {

    private final AtomicBoolean autoCommit;
    private final AtomicInteger autoCommitChanges = new AtomicInteger();
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private final List<String> events = new ArrayList<>();
    private final Map<FailurePoint, SQLException> failures = new EnumMap<>(FailurePoint.class);

    private Scenario(boolean initialAutoCommit) {
      this.autoCommit = new AtomicBoolean(initialAutoCommit);
    }

    private Scenario fail(FailurePoint point) {
      failures.put(point, new SQLException(point.name().toLowerCase()));
      return this;
    }

    private SQLException failure(FailurePoint point) {
      return failures.get(point);
    }

    private ConnectionProvider provider() {
      Connection connection =
          proxy(
              Connection.class,
              (ignored, method, arguments) -> {
                return switch (method.getName()) {
                  case "getAutoCommit" -> {
                    throwIfConfigured(FailurePoint.GET_AUTO_COMMIT);
                    yield autoCommit.get();
                  }
                  case "setAutoCommit" -> {
                    boolean requested = (Boolean) arguments[0];
                    events.add("auto-commit:" + requested);
                    throwIfConfigured(
                        requested
                            ? FailurePoint.RESTORE_AUTO_COMMIT
                            : FailurePoint.DISABLE_AUTO_COMMIT);
                    autoCommit.set(requested);
                    autoCommitChanges.incrementAndGet();
                    yield null;
                  }
                  case "commit" -> {
                    commits.incrementAndGet();
                    events.add("commit");
                    throwIfConfigured(FailurePoint.COMMIT);
                    yield null;
                  }
                  case "rollback" -> {
                    rollbacks.incrementAndGet();
                    events.add("rollback");
                    throwIfConfigured(FailurePoint.ROLLBACK);
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                };
              });
      return new ConnectionProvider() {
        @Override
        public Connection acquire(@NonNull ExecutionContext context) {
          return connection;
        }

        @Override
        public void release(Connection released, ExecutionContext context) throws SQLException {
          assertSame(connection, released);
          releases.incrementAndGet();
          events.add("release");
          throwIfConfigured(FailurePoint.RELEASE);
        }
      };
    }

    private void throwIfConfigured(FailurePoint point) throws SQLException {
      SQLException failure = failures.get(point);
      if (failure != null) {
        throw failure;
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive() || type == void.class) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    if (type == char.class) {
      return '\0';
    }
    throw new AssertionError(type);
  }
}
