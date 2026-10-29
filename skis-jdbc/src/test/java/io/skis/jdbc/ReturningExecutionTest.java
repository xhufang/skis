package io.skis.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.*;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.spi.*;
import io.skis.dialect.spi.RenderedSql;
import io.skis.jdbc.internal.CompiledMutationPlan;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.spi.RowDecoder;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReturningExecutionTest {
  @Test
  void optionalReturningKeepsZeroOrOneRowsAndAllFailureResourceBoundaries() {
    for (var rows : List.of(List.<String>of(), List.of("one"))) {
      var scenario = new Scenario(rows);
      String result = new JdbcExecutor(scenario.provider()).executeReturningOptional(scenario.plan(),
          (rs, context) -> { scenario.step("decode"); return rs.getString(1); },
          1, "input", ExecutionContext.EMPTY, null, StatementPurpose.PRIMARY);
      assertEquals(rows.isEmpty() ? null : "one", result);
      assertEquals("release", scenario.events.getLast());
    }
    var many = new Scenario(List.of("a", "b"));
    assertThrows(MutationException.class, () -> new JdbcExecutor(many.provider()).executeReturningOptional(
        many.plan(), (rs, context) -> rs.getString(1), 1, "input", ExecutionContext.EMPTY, null, StatementPurpose.PRIMARY));
    for (String phase : List.of("acquire", "prepare", "configure", "bind", "execute", "metadata", "next", "decode", "close-rows", "close-statement", "release")) {
      var scenario = new Scenario(); scenario.failures.add(phase);
      assertThrows(JdbcExecutionException.class, () -> new JdbcExecutor(scenario.provider()).executeReturningOptional(
          scenario.plan(), (rs, context) -> { scenario.step("decode"); return rs.getString(1); },
          1, "input", ExecutionContext.EMPTY, null, StatementPurpose.PRIMARY));
      if (!phase.equals("acquire")) assertEquals("release", scenario.events.getLast());
      assertTrue(Collections.frequency(scenario.events, "execute") <= 1);
    }
  }

  @Test
  void decodesByIndexAndClosesBeforeReturningDetachedUnmodifiableRows() {
    var s = new Scenario();
    var rows = execute(s, false);
    assertEquals(List.of("returned"), rows);
    assertThrows(UnsupportedOperationException.class, () -> rows.add("more"));
    assertEquals(List.of("acquire", "prepare", "bind", "configure", "execute", "metadata",
        "next", "decode", "read", "next", "close-rows", "close-statement", "release"), s.events);
    var single = new Scenario();
    String value = new JdbcExecutor(single.provider()).executeReturningOne(single.plan(),
        (rs, context) -> { single.step("decode"); return rs.getString(1); },
        1, "input", ExecutionContext.EMPTY, null, StatementPurpose.PRIMARY);
    assertEquals("returned", value);
    assertEquals(s.events, single.events);
  }

  @Test
  void distinguishesZeroManyNullAndWrongColumnCounts() {
    var empty = new Scenario();
    empty.rows = List.of();
    assertTrue(execute(empty, false).isEmpty());
    assertEquals(SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
        assertThrows(MutationException.class, () -> execute(new Scenario(List.of()), true)).code());
    var many = new Scenario(List.of("a", "b"));
    assertEquals(2, execute(many, false).size());
    assertEquals(SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
        assertThrows(MutationException.class, () -> execute(new Scenario(List.of("a", "b")), true)).code());
    for (boolean single : List.of(false, true)) {
      for (int columns : new int[] {0, 2}) {
        var wrong = new Scenario();
        wrong.columns = columns;
        assertEquals(SkisErrorCode.MUTATION_RETURNING_SHAPE,
            assertThrows(MutationException.class, () -> execute(wrong, single)).code());
        assertEquals("release", wrong.events.getLast());
      }
      var nullRow = new Scenario(Collections.singletonList(null));
      assertEquals(SkisErrorCode.MUTATION_RETURNING_SHAPE,
          assertThrows(MutationException.class, () -> execute(nullRow, single)).code());
      var metadata = new Scenario();
      metadata.nullMetadata = true;
      assertThrows(MutationException.class, () -> execute(metadata, single));
    }
  }

  @Test
  void preservesEveryFailurePhaseAndClosesOnlyAcquiredResourcesInOrder() {
    var phases = Map.ofEntries(
        Map.entry("acquire", JdbcPhase.ACQUIRE), Map.entry("prepare", JdbcPhase.EXECUTE),
        Map.entry("configure", JdbcPhase.CONFIGURE), Map.entry("bind", JdbcPhase.EXECUTE),
        Map.entry("execute", JdbcPhase.EXECUTE), Map.entry("metadata", JdbcPhase.EXECUTE),
        Map.entry("next", JdbcPhase.EXECUTE), Map.entry("decode", JdbcPhase.EXECUTE),
        Map.entry("read", JdbcPhase.EXECUTE), Map.entry("close-rows", JdbcPhase.RESULT_SET_CLOSE),
        Map.entry("close-statement", JdbcPhase.STATEMENT_CLOSE), Map.entry("release", JdbcPhase.RELEASE));
    for (boolean single : List.of(false, true)) {
      for (var point : phases.entrySet()) {
        var s = new Scenario();
        s.failures.add(point.getKey());
        var thrown = assertThrows(JdbcExecutionException.class, () -> execute(s, single), point.getKey());
        assertEquals(point.getValue(), thrown.phase(), point.getKey());
        if (!point.getKey().equals("acquire")) { assertEquals("release", s.events.getLast()); }
        int rowClose = s.events.indexOf("close-rows");
        int statementClose = s.events.indexOf("close-statement");
        if (rowClose >= 0) { assertTrue(rowClose < statementClose); }
        if (statementClose >= 0) { assertTrue(statementClose < s.events.indexOf("release")); }
        assertTrue(Collections.frequency(s.events, "execute") <= 1);
      }
    }
  }

  @Test
  void keepsPrimaryAndSuppressedFailuresAndOneStatementCompletion() {
    var s = new Scenario();
    s.failures.addAll(List.of("decode", "close-rows", "close-statement", "release"));
    var completed = new ArrayList<StatementCompletedEvent>();
    var dispatcher = ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {
      @Override public void statementCompleted(StatementCompletedEvent event) { completed.add(event); }
    }));
    var observation = dispatcher.startOperation(ExecutionOperationKind.INSERT, "returning-test");
    var thrown = assertThrows(JdbcExecutionException.class, () -> new JdbcExecutor(s.provider())
        .executeReturningOne(s.plan(), (rs, context) -> { s.step("decode"); return rs.getString(1); },
            1, "input", ExecutionContext.EMPTY, observation, StatementPurpose.PRIMARY));
    assertSame(s.failure, thrown.getCause());
    assertEquals(2, s.failure.getSuppressed().length);
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals(1, completed.size());
    assertEquals(ExecutionOutcome.FAILED, completed.getFirst().outcome());
    assertEquals("release", s.events.getLast());
  }

  @Test
  void refusesTruncatingOptionsBeforeAcquisitionAndHonorsExplicitUnlimitedOverride() {
    var s = new Scenario();
    var executor = new JdbcExecutor(s.provider());
    assertThrows(MutationException.class, () -> executor.executeReturning(s.plan(),
        (rs, context) -> "unused", 1, "input",
        ExecutionContext.of(ExecutionOptions.builder().maxRows(1).build()), null, StatementPurpose.PRIMARY));
    assertThrows(MutationException.class, () -> executor.executeReturningOne(s.plan(),
        (rs, context) -> "unused", 1, "input",
        ExecutionContext.of(ExecutionOptions.builder().maxRows(1).build()), null, StatementPurpose.PRIMARY));
    assertTrue(s.events.isEmpty());
    assertEquals(List.of("returned"), executor
        .withDefaultExecutionOptions(ExecutionOptions.builder().maxRows(1).build())
        .executeReturning(s.plan(), (rs, context) -> rs.getString(1), 1, "input",
            ExecutionContext.of(ExecutionOptions.builder().maxRows(0).build()), null, StatementPurpose.PRIMARY));
    var single = new Scenario();
    assertEquals("returned", new JdbcExecutor(single.provider())
        .withDefaultExecutionOptions(ExecutionOptions.builder().maxRows(1).build())
        .executeReturningOne(single.plan(), (rs, context) -> rs.getString(1), 1, "input",
            ExecutionContext.of(ExecutionOptions.builder().maxRows(0).build()), null, StatementPurpose.PRIMARY));
  }

  @Test
  void malformedBinderIsReportedAsBindingFailureBeforeExecution() {
    var s = new Scenario();
    var malformed = new CompiledMutationPlan<String>("insert", "returning-test",
        new RenderedSql("INSERT INTO item DEFAULT VALUES RETURNING name", List.of()),
        (statement, firstIndex, value, context) -> firstIndex + 1);
    var failure = assertThrows(JdbcExecutionException.class,
        () -> new JdbcExecutor(s.provider()).executeReturningOne(malformed,
            (rs, context) -> "unused", 1, "input", ExecutionContext.EMPTY, null, StatementPurpose.PRIMARY));
    assertEquals(JdbcPhase.BIND, failure.phase());
    assertFalse(s.events.contains("execute"));
    assertEquals("release", s.events.getLast());
  }

  private static List<String> execute(Scenario s, boolean single) {
    RowDecoder<String> decoder = (rs, context) -> {
      s.step("decode"); return rs.getString(1);
    };
    var executor = new JdbcExecutor(s.provider());
    return single
        ? List.of(executor.executeReturningOne(s.plan(), decoder, 1, "input",
            ExecutionContext.EMPTY, null, StatementPurpose.PRIMARY))
        : executor.executeReturning(s.plan(), decoder, 1, "input",
            ExecutionContext.EMPTY, null, StatementPurpose.PRIMARY);
  }

  private static final class Scenario {
    final List<String> events = new ArrayList<>();
    final Set<String> failures = new HashSet<>();
    final SQLException failure = new SQLException("sensitive driver value", "22003", 47);
    List<String> rows;
    int columns = 1;
    boolean nullMetadata;
    int row = -1;
    Scenario() { this(List.of("returned")); }
    Scenario(List<String> rows) { this.rows = rows; }

    void step(String name) throws SQLException {
      events.add(name);
      if (failures.contains(name)) {
        if (name.startsWith("close-") || name.equals("release")) { throw new SQLException(name, "08006"); }
        throw failure;
      }
    }

    CompiledMutationPlan<String> plan() {
      return new CompiledMutationPlan<>("insert", "returning-test",
          new RenderedSql("INSERT INTO item DEFAULT VALUES RETURNING name", List.of()),
          (statement, firstIndex, value, context) -> { step("bind"); return firstIndex; });
    }

    ConnectionProvider provider() {
      var metadata = proxy(ResultSetMetaData.class, (ignored, method, args) -> columns);
      var resultSet = proxy(ResultSet.class, (ignored, method, args) -> switch (method.getName()) {
        case "getMetaData" -> { step("metadata"); yield nullMetadata ? null : metadata; }
        case "next" -> { step("next"); yield ++row < rows.size(); }
        case "getString" -> { assertEquals(1, args[0]); step("read"); yield rows.get(row); }
        case "close" -> { step("close-rows"); yield null; }
        default -> throw new AssertionError(method.getName());
      });
      var statement = proxy(PreparedStatement.class, (ignored, method, args) -> switch (method.getName()) {
        case "getMaxRows" -> 0;
        case "setMaxRows" -> null;
        case "executeQuery" -> { step("execute"); yield resultSet; }
        case "close" -> { step("close-statement"); yield null; }
        default -> throw new AssertionError(method.getName());
      });
      var connection = proxy(Connection.class, (ignored, method, args) -> {
        assertEquals("prepareStatement", method.getName());
        assertEquals(1, args.length, "RETURNING must not request JDBC generated keys");
        step("prepare"); return statement;
      });
      return new ConnectionProvider() {
        @Override public Connection acquire(ExecutionContext context) throws SQLException {
          step("acquire"); return connection;
        }
        @Override public void configureStatement(PreparedStatement prepared, ExecutionContext context,
            int timeout) throws SQLException { step("configure"); }
        @Override public void release(Connection acquired, ExecutionContext context) throws SQLException { step("release"); }
      };
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }
}
