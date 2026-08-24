package io.skis.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.core.ExecutionContext;
import io.skis.core.TransactionException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class JdbcTransactionTest {

  @Test
  void commitsThenRestoresAndReleasesTheOwnedConnection() {
    Scenario scenario = new Scenario();

    try (JdbcTransaction transaction = JdbcTransaction.begin(scenario.provider())) {
      assertFalse(scenario.autoCommit.get());
      transaction.commit();
    }

    assertEquals(1, scenario.commits.get());
    assertEquals(0, scenario.rollbacks.get());
    assertEquals(1, scenario.releases.get());
    assertEquals(2, scenario.autoCommitChanges.get());
  }

  @Test
  void closingAnActiveTransactionRollsItBack() {
    Scenario scenario = new Scenario();

    try (JdbcTransaction ignored = JdbcTransaction.begin(scenario.provider())) {
      // Closing an uncompleted transaction is the rollback boundary.
    }

    assertEquals(0, scenario.commits.get());
    assertEquals(1, scenario.rollbacks.get());
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

  private static final class Scenario {

    private final AtomicBoolean autoCommit = new AtomicBoolean(true);
    private final AtomicInteger autoCommitChanges = new AtomicInteger();
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();

    private ConnectionProvider provider() {
      Connection connection =
          proxy(
              Connection.class,
              (ignored, method, arguments) -> {
                return switch (method.getName()) {
                  case "getAutoCommit" -> autoCommit.get();
                  case "setAutoCommit" -> {
                    autoCommit.set((Boolean) arguments[0]);
                    autoCommitChanges.incrementAndGet();
                    yield null;
                  }
                  case "commit" -> {
                    commits.incrementAndGet();
                    yield null;
                  }
                  case "rollback" -> {
                    rollbacks.incrementAndGet();
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
          releases.incrementAndGet();
        }
      };
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
