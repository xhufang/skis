package io.skis.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.core.ExecutionContext;
import io.skis.core.NonUniqueResultException;
import io.skis.dialect.RenderedSql;
import io.skis.sql.ast.ParameterSlot;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class JdbcExecutorTest {

  @Test
  void bindsDecodesAndClosesEveryJdbcResource() {
    Scenario scenario = new Scenario(List.of("Mimi", "Fifi"));
    JdbcExecutor executor = new JdbcExecutor(scenario.provider());

    List<String> result = executor.fetchList(plan(), 17L);

    assertEquals(List.of("Mimi", "Fifi"), result);
    assertEquals(1, scenario.boundIndex.get());
    assertEquals(17L, scenario.boundValue.get());
    assertEquals(1, scenario.resultSetCloses.get());
    assertEquals(1, scenario.statementCloses.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void detectsNonUniqueRowsAndStillReleasesResources() {
    Scenario scenario = new Scenario(List.of("Mimi", "Fifi"));
    JdbcExecutor executor = new JdbcExecutor(scenario.provider());

    assertThrows(NonUniqueResultException.class, () -> executor.fetchOne(plan(), 1L));

    assertEquals(1, scenario.resultSetCloses.get());
    assertEquals(1, scenario.statementCloses.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void preservesExecutionFailureAndSuppressesReleaseFailure() {
    SQLException executionFailure = new SQLException("execute failed", "42000", 9);
    SQLException releaseFailure = new SQLException("release failed", "08006", 12);
    Scenario scenario = new Scenario(List.of());
    scenario.executionFailure = executionFailure;
    scenario.releaseFailure = releaseFailure;
    JdbcExecutor executor = new JdbcExecutor(scenario.provider());

    QueryExecutionException thrown =
        assertThrows(QueryExecutionException.class, () -> executor.fetchList(plan(), 1L));

    assertSame(executionFailure, thrown.getCause());
    assertEquals("42000", thrown.sqlState());
    assertEquals(9, thrown.vendorCode());
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(releaseFailure, thrown.getSuppressed()[0]);
  }

  private static CompiledQueryPlan<String, Long> plan() {
    ParameterSlot<Long> id = new ParameterSlot<>(0, Long.class, false);
    return new CompiledQueryPlan<>(
        "test",
        new RenderedSql("SELECT name FROM pet WHERE id = ?", List.of(id)),
        (statement, firstIndex, value, context) -> {
          statement.setLong(firstIndex, value);
          return firstIndex + 1;
        },
        (resultSet, context) -> resultSet.getString(1));
  }

  private static final class Scenario {

    private final List<String> rows;
    private final AtomicInteger cursor = new AtomicInteger(-1);
    private final AtomicInteger boundIndex = new AtomicInteger();
    private final AtomicLong boundValue = new AtomicLong();
    private final AtomicInteger resultSetCloses = new AtomicInteger();
    private final AtomicInteger statementCloses = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private SQLException executionFailure;
    private SQLException releaseFailure;

    private Scenario(List<String> rows) {
      this.rows = new ArrayList<>(rows);
    }

    private ConnectionProvider provider() {
      ResultSet resultSet =
          proxy(
              ResultSet.class,
              (ignored, method, arguments) -> {
                return switch (method.getName()) {
                  case "next" -> cursor.incrementAndGet() < rows.size();
                  case "getString" -> rows.get(cursor.get());
                  case "close" -> {
                    resultSetCloses.incrementAndGet();
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                };
              });
      PreparedStatement statement =
          proxy(
              PreparedStatement.class,
              (ignored, method, arguments) -> {
                return switch (method.getName()) {
                  case "setLong" -> {
                    boundIndex.set((Integer) arguments[0]);
                    boundValue.set((Long) arguments[1]);
                    yield null;
                  }
                  case "executeQuery" -> {
                    if (executionFailure != null) {
                      throw executionFailure;
                    }
                    yield resultSet;
                  }
                  case "close" -> {
                    statementCloses.incrementAndGet();
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                };
              });
      Connection connection =
          proxy(
              Connection.class,
              (ignored, method, arguments) ->
                  method.getName().equals("prepareStatement")
                      ? statement
                      : defaultValue(method.getReturnType()));
      return new ConnectionProvider() {
        @Override
        public Connection acquire(ExecutionContext context) {
          return connection;
        }

        @Override
        public void release(Connection released, ExecutionContext context) throws SQLException {
          releases.incrementAndGet();
          if (releaseFailure != null) {
            throw releaseFailure;
          }
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
    if (type == char.class) {
      return '\0';
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
    throw new AssertionError(type);
  }
}
