package io.skis.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class JdbcExecutorTest {

  private static final String QUERY_SQL = "SELECT name FROM pet WHERE id = ?";
  private static final String MUTATION_SQL = "DELETE FROM pet WHERE name = ?";
  private static final String SENSITIVE_PARAMETER = "secret-customer-value";

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
    SQLException statementCloseFailure = new SQLException("statement close failed", "HY000", 10);
    SQLException releaseFailure = new SQLException("release failed", "08006", 12);
    Scenario scenario = new Scenario(List.of());
    scenario.executionFailure = executionFailure;
    scenario.statementCloseFailure = statementCloseFailure;
    scenario.releaseFailure = releaseFailure;
    JdbcExecutor executor = new JdbcExecutor(scenario.provider());

    QueryExecutionException thrown =
        assertThrows(QueryExecutionException.class, () -> executor.fetchList(plan(), 1L));

    assertSame(executionFailure, thrown.getCause());
    assertEquals("42000", thrown.sqlState());
    assertEquals(9, thrown.vendorCode());
    assertDiagnosticMessage(thrown, "query", "execution", "test", QUERY_SQL);
    assertEquals(1, executionFailure.getSuppressed().length);
    assertSame(statementCloseFailure, executionFailure.getSuppressed()[0]);
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(releaseFailure, thrown.getSuppressed()[0]);
  }

  @Test
  void executesMutationAndValidatesGeneratedBinderShape() {
    Scenario scenario = new Scenario(List.of());
    JdbcExecutor executor = new JdbcExecutor(scenario.provider());
    ParameterSlot<Long> id = new ParameterSlot<>(0, Long.class, false);
    CompiledMutationPlan<Long> mutation =
        new CompiledMutationPlan<>(
            "deleteById",
            "test",
            new RenderedSql("DELETE FROM pet WHERE id = ?", List.of(id)),
            (statement, firstIndex, value, context) -> {
              statement.setLong(firstIndex, value);
              return firstIndex + 1;
            });

    int affected = executor.executeUpdate(mutation, 23L);

    assertEquals(1, affected);
    assertEquals(1, scenario.boundIndex.get());
    assertEquals(23L, scenario.boundValue.get());
    assertEquals(1, scenario.statementCloses.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void diagnosesQueryAcquireFailureWithoutReleasingOrLeakingParameters() {
    SQLException acquireFailure = new SQLException("pool exhausted", "08001", 17);
    Scenario scenario = new Scenario(List.of());
    scenario.acquireFailure = acquireFailure;
    JdbcExecutor executor = new JdbcExecutor(scenario.provider());

    QueryExecutionException thrown =
        assertThrows(
            QueryExecutionException.class,
            () -> executor.fetchList(sensitivePlan(), SENSITIVE_PARAMETER));

    assertSame(acquireFailure, thrown.getCause());
    assertEquals("08001", thrown.sqlState());
    assertEquals(17, thrown.vendorCode());
    assertDiagnosticMessage(thrown, "query", "connection-acquire", "test", QUERY_SQL);
    assertEquals(0, scenario.statementCloses.get());
    assertEquals(0, scenario.resultSetCloses.get());
    assertEquals(0, scenario.releases.get());
  }

  @Test
  void diagnosesQueryReleaseFailureAfterClosingStatementAndResultSet() {
    SQLException releaseFailure = new SQLException("release failed", "08006", 29);
    Scenario scenario = new Scenario(List.of());
    scenario.releaseFailure = releaseFailure;
    JdbcExecutor executor = new JdbcExecutor(scenario.provider());

    QueryExecutionException thrown =
        assertThrows(
            QueryExecutionException.class,
            () -> executor.fetchList(sensitivePlan(), SENSITIVE_PARAMETER));

    assertSame(releaseFailure, thrown.getCause());
    assertEquals("08006", thrown.sqlState());
    assertEquals(29, thrown.vendorCode());
    assertDiagnosticMessage(thrown, "query", "connection-release", "test", QUERY_SQL);
    assertEquals(1, scenario.resultSetCloses.get());
    assertEquals(1, scenario.statementCloses.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void releasesConnectionWhenStatementPreparationFails() {
    SQLException prepareFailure = new SQLException("prepare failed", "42000", 31);
    Scenario scenario = new Scenario(List.of());
    scenario.prepareFailure = prepareFailure;
    JdbcExecutor executor = new JdbcExecutor(scenario.provider());

    QueryExecutionException thrown =
        assertThrows(
            QueryExecutionException.class,
            () -> executor.fetchList(sensitivePlan(), SENSITIVE_PARAMETER));

    assertSame(prepareFailure, thrown.getCause());
    assertDiagnosticMessage(thrown, "query", "execution", "test", QUERY_SQL);
    assertEquals(0, scenario.resultSetCloses.get());
    assertEquals(0, scenario.statementCloses.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void preservesBinderStatementCloseAndReleaseFailuresAtTheirOwningLevels() {
    SQLException bindFailure = new SQLException("bind failed", "22000", 41);
    SQLException statementCloseFailure = new SQLException("statement close failed", "HY000", 42);
    SQLException releaseFailure = new SQLException("release failed", "08006", 43);
    Scenario scenario = new Scenario(List.of());
    scenario.statementCloseFailure = statementCloseFailure;
    scenario.releaseFailure = releaseFailure;
    JdbcExecutor executor = new JdbcExecutor(scenario.provider());

    QueryExecutionException thrown =
        assertThrows(
            QueryExecutionException.class,
            () -> executor.fetchList(bindingFailurePlan(bindFailure), SENSITIVE_PARAMETER));

    assertSame(bindFailure, thrown.getCause());
    assertDiagnosticMessage(thrown, "query", "execution", "test", QUERY_SQL);
    assertEquals(1, bindFailure.getSuppressed().length);
    assertSame(statementCloseFailure, bindFailure.getSuppressed()[0]);
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(releaseFailure, thrown.getSuppressed()[0]);
    assertEquals(0, scenario.resultSetCloses.get());
    assertEquals(1, scenario.statementCloses.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void preservesDecoderResultSetStatementAndReleaseFailuresInCloseOrder() {
    SQLException decodeFailure = new SQLException("decode failed", "22000", 51);
    SQLException resultSetCloseFailure = new SQLException("result set close failed", "HY000", 52);
    SQLException statementCloseFailure = new SQLException("statement close failed", "HY000", 53);
    SQLException releaseFailure = new SQLException("release failed", "08006", 54);
    Scenario scenario = new Scenario(List.of("Mimi"));
    scenario.resultSetCloseFailure = resultSetCloseFailure;
    scenario.statementCloseFailure = statementCloseFailure;
    scenario.releaseFailure = releaseFailure;
    JdbcExecutor executor = new JdbcExecutor(scenario.provider());

    QueryExecutionException thrown =
        assertThrows(
            QueryExecutionException.class,
            () -> executor.fetchList(decodingFailurePlan(decodeFailure), SENSITIVE_PARAMETER));

    assertSame(decodeFailure, thrown.getCause());
    assertDiagnosticMessage(thrown, "query", "execution", "test", QUERY_SQL);
    assertEquals(2, decodeFailure.getSuppressed().length);
    assertSame(resultSetCloseFailure, decodeFailure.getSuppressed()[0]);
    assertSame(statementCloseFailure, decodeFailure.getSuppressed()[1]);
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(releaseFailure, thrown.getSuppressed()[0]);
    assertEquals(1, scenario.resultSetCloses.get());
    assertEquals(1, scenario.statementCloses.get());
    assertEquals(1, scenario.releases.get());
  }

  @Test
  void distinguishesMutationAcquireExecutionAndReleaseFailures() {
    SQLException acquireFailure = new SQLException("pool exhausted", "08001", 61);
    Scenario acquireScenario = new Scenario(List.of());
    acquireScenario.acquireFailure = acquireFailure;

    JdbcExecutionException acquireThrown =
        assertThrows(
            JdbcExecutionException.class,
            () ->
                new JdbcExecutor(acquireScenario.provider())
                    .executeUpdate(sensitiveMutationPlan(), SENSITIVE_PARAMETER));

    assertSame(acquireFailure, acquireThrown.getCause());
    assertDiagnosticMessage(
        acquireThrown, "deleteById", "connection-acquire", "test", MUTATION_SQL);
    assertEquals(0, acquireScenario.releases.get());

    SQLException executionFailure = new SQLException("execute failed", "42000", 62);
    Scenario executionScenario = new Scenario(List.of());
    executionScenario.executionFailure = executionFailure;

    JdbcExecutionException executionThrown =
        assertThrows(
            JdbcExecutionException.class,
            () ->
                new JdbcExecutor(executionScenario.provider())
                    .executeUpdate(sensitiveMutationPlan(), SENSITIVE_PARAMETER));

    assertSame(executionFailure, executionThrown.getCause());
    assertDiagnosticMessage(executionThrown, "deleteById", "execution", "test", MUTATION_SQL);
    assertEquals(1, executionScenario.statementCloses.get());
    assertEquals(1, executionScenario.releases.get());

    SQLException releaseFailure = new SQLException("release failed", "08006", 63);
    Scenario releaseScenario = new Scenario(List.of());
    releaseScenario.releaseFailure = releaseFailure;

    JdbcExecutionException releaseThrown =
        assertThrows(
            JdbcExecutionException.class,
            () ->
                new JdbcExecutor(releaseScenario.provider())
                    .executeUpdate(sensitiveMutationPlan(), SENSITIVE_PARAMETER));

    assertSame(releaseFailure, releaseThrown.getCause());
    assertDiagnosticMessage(
        releaseThrown, "deleteById", "connection-release", "test", MUTATION_SQL);
    assertEquals(1, releaseScenario.statementCloses.get());
    assertEquals(1, releaseScenario.releases.get());
  }

  private static CompiledQueryPlan<String, Long> plan() {
    ParameterSlot<Long> id = new ParameterSlot<>(0, Long.class, false);
    return new CompiledQueryPlan<>(
        "test",
        new RenderedSql(QUERY_SQL, List.of(id)),
        (statement, firstIndex, value, context) -> {
          statement.setLong(firstIndex, value);
          return firstIndex + 1;
        },
        (resultSet, context) -> resultSet.getString(1));
  }

  private static CompiledQueryPlan<String, String> sensitivePlan() {
    ParameterSlot<String> value = new ParameterSlot<>(0, String.class, false);
    return new CompiledQueryPlan<>(
        "test",
        new RenderedSql(QUERY_SQL, List.of(value)),
        (statement, firstIndex, parameter, context) -> {
          statement.setString(firstIndex, parameter);
          return firstIndex + 1;
        },
        (resultSet, context) -> resultSet.getString(1));
  }

  private static CompiledQueryPlan<String, String> bindingFailurePlan(SQLException failure) {
    ParameterSlot<String> value = new ParameterSlot<>(0, String.class, false);
    return new CompiledQueryPlan<>(
        "test",
        new RenderedSql(QUERY_SQL, List.of(value)),
        (statement, firstIndex, parameter, context) -> {
          throw failure;
        },
        (resultSet, context) -> resultSet.getString(1));
  }

  private static CompiledQueryPlan<String, String> decodingFailurePlan(SQLException failure) {
    ParameterSlot<String> value = new ParameterSlot<>(0, String.class, false);
    return new CompiledQueryPlan<>(
        "test",
        new RenderedSql(QUERY_SQL, List.of(value)),
        (statement, firstIndex, parameter, context) -> {
          statement.setString(firstIndex, parameter);
          return firstIndex + 1;
        },
        (resultSet, context) -> {
          throw failure;
        });
  }

  private static CompiledMutationPlan<String> sensitiveMutationPlan() {
    ParameterSlot<String> value = new ParameterSlot<>(0, String.class, false);
    return new CompiledMutationPlan<>(
        "deleteById",
        "test",
        new RenderedSql(MUTATION_SQL, List.of(value)),
        (statement, firstIndex, parameter, context) -> {
          statement.setString(firstIndex, parameter);
          return firstIndex + 1;
        });
  }

  private static void assertDiagnosticMessage(
      RuntimeException failure, String operation, String phase, String dialectId, String sql) {
    String message = Objects.requireNonNull(failure.getMessage(), "failure message");
    assertTrue(message.startsWith("JDBC " + operation + " failed [phase=" + phase));
    assertTrue(message.contains("dialect=" + dialectId));
    assertTrue(
        message.contains("sqlFingerprint=" + QueryExecutionException.fingerprint(sql)));
    assertFalse(message.contains(sql));
    assertFalse(message.contains(SENSITIVE_PARAMETER));
    if (failure.getCause() instanceof SQLException sqlFailure) {
      String sqlState = sqlFailure.getSQLState();
      assertTrue(
          message.contains(
              "sqlState=" + (sqlState == null || sqlState.isBlank() ? "unknown" : sqlState)));
      assertTrue(message.contains("vendorCode=" + sqlFailure.getErrorCode()));
    }
  }

  private static final class Scenario {

    private final List<String> rows;
    private final AtomicInteger cursor = new AtomicInteger(-1);
    private final AtomicInteger boundIndex = new AtomicInteger();
    private final AtomicLong boundValue = new AtomicLong();
    private final AtomicInteger resultSetCloses = new AtomicInteger();
    private final AtomicInteger statementCloses = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private @Nullable SQLException acquireFailure;
    private @Nullable SQLException prepareFailure;
    private @Nullable SQLException executionFailure;
    private @Nullable SQLException resultSetCloseFailure;
    private @Nullable SQLException statementCloseFailure;
    private @Nullable SQLException releaseFailure;

    private Scenario(List<String> rows) {
      this.rows = new ArrayList<>(rows);
    }

    private ConnectionProvider provider() {
      ResultSet resultSet =
          proxy(
              ResultSet.class,
              (ignored, method, arguments) -> switch (method.getName()) {
                case "next" -> cursor.incrementAndGet() < rows.size();
                case "getString" -> rows.get(cursor.get());
                case "close" -> {
                  resultSetCloses.incrementAndGet();
                  if (resultSetCloseFailure != null) {
                    throw resultSetCloseFailure;
                  }
                  yield null;
                }
                default -> defaultValue(method.getReturnType());
              });
      PreparedStatement statement =
          proxy(
              PreparedStatement.class,
              (ignored, method, arguments) -> switch (method.getName()) {
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
                case "executeUpdate" -> {
                  if (executionFailure != null) {
                    throw executionFailure;
                  }
                  yield 1;
                }
                case "close" -> {
                  statementCloses.incrementAndGet();
                  if (statementCloseFailure != null) {
                    throw statementCloseFailure;
                  }
                  yield null;
                }
                default -> defaultValue(method.getReturnType());
              });
      Connection connection =
          proxy(
              Connection.class,
              (ignored, method, arguments) -> {
                if (method.getName().equals("prepareStatement")) {
                  if (prepareFailure != null) {
                    throw prepareFailure;
                  }
                  return statement;
                }
                return defaultValue(method.getReturnType());
              });
      return new ConnectionProvider() {
        @Override
        public Connection acquire(ExecutionContext context) throws SQLException {
          if (acquireFailure != null) {
            throw acquireFailure;
          }
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
