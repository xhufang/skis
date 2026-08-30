package io.skis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.core.ExecutionContext;
import io.skis.core.TransactionException;
import io.skis.jdbc.ConnectionProvider;
import io.skis.jdbc.JdbcTransaction;
import io.skis.mutation.MutationOperations;
import io.skis.query.QueryOperations;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultSkisSessionTest {

  @Test
  void runsEveryCommittedCallbackOnceAndAggregatesCallbackFailures() {
    Scenario scenario = new Scenario(false);
    DefaultSkisSession session = scenario.session();
    List<String> calls = new ArrayList<>();
    IllegalStateException first = new IllegalStateException("first callback");
    IllegalArgumentException second = new IllegalArgumentException("second callback");
    session.afterCommit(
        () -> {
          calls.add("first");
          throw first;
        });
    session.afterCommit(
        () -> {
          calls.add("second");
          throw second;
        });
    session.afterCommit(() -> calls.add("third"));

    TransactionException failure = assertThrows(TransactionException.class, session::commit);

    assertEquals(List.of("first", "second", "third"), calls);
    assertSame(first, failure.getCause());
    assertEquals(1, first.getSuppressed().length);
    assertSame(second, first.getSuppressed()[0]);
    assertFalse(session.active());
    session.close();
    assertEquals(1, scenario.commits.get());
    assertEquals(0, scenario.rollbacks.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void discardsCallbacksWhenCommitOutcomeIsUnknown() {
    Scenario scenario = new Scenario(true);
    DefaultSkisSession session = scenario.session();
    AtomicInteger callbacks = new AtomicInteger();
    session.afterCommit(callbacks::incrementAndGet);

    TransactionException failure = assertThrows(TransactionException.class, session::commit);
    session.close();

    assertSame(scenario.commitFailure, failure.getCause());
    assertEquals(0, callbacks.get());
    assertEquals(0, scenario.rollbacks.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void rollbackAndImplicitCloseDiscardCallbacks() {
    Scenario rolledBack = new Scenario(false);
    DefaultSkisSession rolledBackSession = rolledBack.session();
    AtomicInteger callbacks = new AtomicInteger();
    rolledBackSession.afterCommit(callbacks::incrementAndGet);

    rolledBackSession.rollback();
    rolledBackSession.close();

    Scenario implicitlyClosed = new Scenario(false);
    DefaultSkisSession implicitlyClosedSession = implicitlyClosed.session();
    implicitlyClosedSession.afterCommit(callbacks::incrementAndGet);
    implicitlyClosedSession.close();

    assertEquals(0, callbacks.get());
    assertEquals(1, rolledBack.rollbacks.get());
    assertEquals(1, implicitlyClosed.rollbacks.get());
  }

  @Test
  void rejectsLateCallbackRegistrationAfterCommit() {
    Scenario scenario = new Scenario(false);
    DefaultSkisSession session = scenario.session();
    session.commit();

    assertThrows(TransactionException.class, () -> session.afterCommit(() -> {}));
    session.close();
  }

  private static final class Scenario {

    private final SQLException commitFailure = new SQLException("commit failed");
    private final boolean failCommit;
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();

    private Scenario(boolean failCommit) {
      this.failCommit = failCommit;
    }

    private DefaultSkisSession session() {
      Connection connection =
          proxy(
              Connection.class,
              (ignored, method, arguments) -> {
                return switch (method.getName()) {
                  case "getAutoCommit" -> true;
                  case "commit" -> {
                    commits.incrementAndGet();
                    if (failCommit) {
                      throw commitFailure;
                    }
                    yield null;
                  }
                  case "rollback" -> {
                    rollbacks.incrementAndGet();
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                };
              });
      ConnectionProvider provider =
          new ConnectionProvider() {
            @Override
            public Connection acquire(ExecutionContext context) {
              return connection;
            }

            @Override
            public void release(Connection released, ExecutionContext context) {
              assertSame(connection, released);
              releases.incrementAndGet();
            }
          };
      JdbcTransaction transaction = JdbcTransaction.begin(provider);
      return new DefaultSkisSession(
          transaction, noOp(QueryOperations.class), noOp(MutationOperations.class));
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T noOp(Class<T> type) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (ignored, method, arguments) -> defaultValue(method.getReturnType()));
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive() || type == void.class) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
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
