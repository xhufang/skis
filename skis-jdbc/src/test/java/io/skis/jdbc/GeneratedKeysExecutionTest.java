package io.skis.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.MutationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.spi.*;
import io.skis.dialect.spi.RenderedSql;
import io.skis.jdbc.internal.CompiledMutationPlan;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.spi.JdbcCodecs;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GeneratedKeysExecutionTest {
  private static final CompiledMutationPlan<String> PLAN = new CompiledMutationPlan<>(
      "insert", "test", new RenderedSql("INSERT INTO item DEFAULT VALUES", List.of()),
      (statement, firstIndex, value, context) -> firstIndex);

  @Test
  void requestsThePhysicalColumnAndDecodesOneKeyWithinOneStatementLifecycle() {
    var scenario = new Scenario();
    var completed = new ArrayList<StatementCompletedEvent>();
    var dispatcher = ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {
      @Override public void statementCompleted(StatementCompletedEvent event) { completed.add(event); }
    }));
    var operation = dispatcher.startOperation(ExecutionOperationKind.INSERT, "test");
    Object key = new JdbcExecutor(scenario.provider()).executeGeneratedKey(PLAN, "input",
        "Generated Key", JdbcCodecs.LONG, ExecutionContext.EMPTY, operation, StatementPurpose.PRIMARY);
    assertEquals(41L, key);
    assertEquals(List.of("Generated Key"), scenario.requestedColumns);
    assertEquals(List.of("acquire", "prepare", "configure", "execute", "keys", "metadata",
        "next", "read", "wasNull", "next", "close-keys", "close-statement", "release"), scenario.events);
    assertEquals(1, completed.size());
  }

  @Test
  void acceptsEveryIntegralJdbcKeyTypeForBothSupportedJavaKeyTypes() {
    for (int jdbcType : new int[] {Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT}) {
      for (var codec : List.of(JdbcCodecs.LONG, JdbcCodecs.INTEGER)) {
        var scenario = new Scenario();
        scenario.columnType = jdbcType;
        Object key = new JdbcExecutor(scenario.provider()).executeGeneratedKey(PLAN, "input", "id",
            codec, ExecutionContext.EMPTY, null, StatementPurpose.PRIMARY);
        if (codec == JdbcCodecs.LONG) {
          assertEquals(41L, key);
        } else {
          assertEquals(41, key);
        }
        assertEquals(List.of("id"), scenario.requestedColumns);
        assertEquals(List.of("acquire", "prepare", "configure", "execute", "keys", "metadata",
            "next", "read", "wasNull", "next", "close-keys", "close-statement", "release"),
            scenario.events);
      }
    }
  }

  @Test
  void rejectsMissingExtraNullOrWrongShapedKeysAndUnexpectedAffectedRows() {
    for (int variant = 0; variant < 7; variant++) {
      var scenario = new Scenario();
      switch (variant) {
        case 0 -> scenario.keys = List.of();
        case 1 -> scenario.keys = Arrays.asList(1L, 2L);
        case 2 -> scenario.keys = Collections.singletonList(null);
        case 3 -> scenario.columns = 2;
        case 4 -> scenario.columns = 0;
        case 5 -> scenario.affected = 0;
        case 6 -> scenario.columnType = Types.VARCHAR;
        default -> throw new AssertionError();
      }
      var failure = assertThrows(MutationException.class, () -> execute(scenario));
      assertEquals(variant == 5 ? SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT
          : SkisErrorCode.MUTATION_GENERATED_KEY_INVALID, failure.code());
      assertTrue(scenario.events.contains("close-statement"));
      assertEquals("release", scenario.events.getLast());
    }
  }

  @Test
  void reportsNullKeyMetadataToTheCallerAndListenerAfterClosingResources() {
    var scenario = new Scenario();
    scenario.nullMetadata = true;
    var completed = new ArrayList<StatementCompletedEvent>();
    var dispatcher = ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {
      @Override public void statementCompleted(StatementCompletedEvent event) { completed.add(event); }
    }));
    var operation = dispatcher.startOperation(ExecutionOperationKind.INSERT, "test");
    var failure = assertThrows(MutationException.class, () -> new JdbcExecutor(scenario.provider())
        .executeGeneratedKey(PLAN, "input", "id", JdbcCodecs.LONG, ExecutionContext.EMPTY,
            operation, StatementPurpose.PRIMARY));
    assertEquals(SkisErrorCode.MUTATION_GENERATED_KEY_INVALID, failure.code());
    assertEquals(1, completed.size());
    assertEquals(ExecutionOutcome.FAILED, completed.getFirst().outcome());
    assertEquals(failure.diagnostics(), completed.getFirst().diagnostics());
    assertEquals(List.of("acquire", "prepare", "configure", "execute", "keys", "metadata",
        "close-keys", "close-statement", "release"), scenario.events);
  }

  @Test
  void closesOwnedResourcesAtEveryJdbcFailurePoint() {
    for (String point : List.of("acquire", "prepare", "configure", "execute", "keys", "metadata",
        "next", "read", "close-keys", "close-statement", "release")) {
      var scenario = new Scenario();
      scenario.failures.add(point);
      var failure = assertThrows(JdbcExecutionException.class, () -> execute(scenario), point);
      assertInstanceOf(SQLException.class, failure.getCause(), point);
      if (!point.startsWith("close-") && !point.equals("release")) {
        assertSame(scenario.failure, failure.getCause(), point);
      }
      assertEquals(point.equals("acquire") ? "acquire" : "release", scenario.events.getLast());
      if (!List.of("acquire", "prepare").contains(point)) {
        assertEquals(1, Collections.frequency(scenario.events, "close-statement"), point);
      }
    }
  }

  @Test
  void preservesReadAndAllCleanupFailuresAndRejectsIntegerOverflow() {
    var scenario = new Scenario();
    scenario.failures.addAll(List.of("read", "close-keys", "close-statement", "release"));
    var failure = assertThrows(JdbcExecutionException.class, () -> execute(scenario));
    assertSame(scenario.failure, failure.getCause());
    assertEquals(2, failure.getCause().getSuppressed().length);
    assertEquals(1, failure.getSuppressed().length);

    var overflow = new Scenario();
    overflow.keys = List.of((long) Integer.MAX_VALUE + 1);
    assertThrows(JdbcExecutionException.class, () -> new JdbcExecutor(overflow.provider())
        .executeGeneratedKey(PLAN, "input", "id", JdbcCodecs.INTEGER, ExecutionContext.EMPTY,
            null, StatementPurpose.PRIMARY));
    assertEquals("release", overflow.events.getLast());
  }

  private static Object execute(Scenario scenario) {
    return new JdbcExecutor(scenario.provider()).executeGeneratedKey(PLAN, "input", "id",
        JdbcCodecs.LONG, ExecutionContext.EMPTY, null, StatementPurpose.PRIMARY);
  }

  private static final class Scenario {
    final List<String> events = new ArrayList<>();
    final Set<String> failures = new HashSet<>();
    final SQLException failure = new SQLException("sensitive driver text", "22003", 47);
    List<Long> keys = List.of(41L);
    List<String> requestedColumns;
    int columns = 1;
    boolean nullMetadata;
    int columnType = Types.BIGINT;
    int affected = 1;
    int row = -1;

    void step(String name) throws SQLException {
      events.add(name);
      if (failures.contains(name)) {
        // Distinct cleanup failures avoid Throwable self-suppression.
        if (name.startsWith("close-") || name.equals("release")) {
          throw new SQLException(name, "08006");
        }
        throw failure;
      }
    }

    ConnectionProvider provider() {
      ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (ignored, method, args) -> {
        if (method.getName().equals("getColumnCount")) { return columns; }
        if (method.getName().equals("getColumnType")) { assertEquals(1, args[0]); return columnType; }
        throw new AssertionError(method.getName());
      });
      ResultSet results = proxy(ResultSet.class, (ignored, method, args) -> {
        return switch (method.getName()) {
          case "getMetaData" -> { step("metadata"); yield nullMetadata ? null : metadata; }
          case "next" -> { step("next"); yield ++row < keys.size(); }
          case "getLong", "getInt" -> {
            assertEquals(1, args[0]);
            step("read");
            Long key = keys.get(row);
            if (method.getName().equals("getInt")) {
              if (key != null && (key > Integer.MAX_VALUE || key < Integer.MIN_VALUE)) {
                throw new SQLException("numeric overflow", "22003");
              }
              yield key == null ? 0 : key.intValue();
            }
            yield key == null ? 0L : key;
          }
          case "wasNull" -> { step("wasNull"); yield keys.get(row) == null; }
          case "close" -> { step("close-keys"); yield null; }
          default -> throw new AssertionError(method.getName());
        };
      });
      PreparedStatement statement = proxy(PreparedStatement.class, (ignored, method, args) -> {
        return switch (method.getName()) {
          case "executeUpdate" -> { step("execute"); yield affected; }
          case "getGeneratedKeys" -> { step("keys"); yield results; }
          case "close" -> { step("close-statement"); yield null; }
          default -> throw new AssertionError(method.getName());
        };
      });
      Connection connection = proxy(Connection.class, (ignored, method, args) -> {
        if (!method.getName().equals("prepareStatement")) { throw new AssertionError(method.getName()); }
        step("prepare");
        requestedColumns = Arrays.asList(((String[]) args[1]).clone());
        ((String[]) args[1])[0] = "driver mutation";
        return statement;
      });
      return new ConnectionProvider() {
        @Override public Connection acquire(ExecutionContext context) throws SQLException {
          step("acquire"); return connection;
        }
        @Override public void configureStatement(PreparedStatement statement, ExecutionContext context,
            int timeout) throws SQLException { step("configure"); }
        @Override public void release(Connection connection, ExecutionContext context) throws SQLException {
          step("release");
        }
      };
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }
}
