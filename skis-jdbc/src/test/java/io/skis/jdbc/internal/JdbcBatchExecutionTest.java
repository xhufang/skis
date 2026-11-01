package io.skis.jdbc.internal;

import static org.junit.jupiter.api.Assertions.*;
import io.skis.core.spi.ExecutionContext;
import io.skis.dialect.spi.RenderedSql;
import io.skis.jdbc.JdbcExecutionException;
import io.skis.jdbc.JdbcPhase;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.sql.ast.ParameterSlot;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JdbcBatchExecutionTest {
  private static final CompiledMutationPlan<Long> PLAN = new CompiledMutationPlan<>("insert", "batch-test",
      new RenderedSql("INSERT INTO item(id) VALUES (?)", List.of(new ParameterSlot<>(0, Long.class, false))),
      (statement, first, value, context) -> { statement.setLong(first, value); return first + 1; });

  @Test void batchAccessCannotEscapeItsConnectionScopeOrSubmitEmptyChunks() {
    Scenario scenario = new Scenario();
    var retained = new java.util.concurrent.atomic.AtomicReference<JdbcExecutor.BatchConnection>();
    var executor = new JdbcExecutor(scenario.provider());
    executor.executeBatchWork(PLAN, ExecutionContext.EMPTY, null, connection -> {
      retained.set(connection);
      assertThrows(IllegalArgumentException.class, () -> connection.addBatch(PLAN, List.of()));
      return connection.addBatch(PLAN, List.of(11L));
    });
    var expired = retained.get();
    assertThrows(IllegalStateException.class, () -> expired.addBatch(PLAN, List.of(12L)));
    assertThrows(IllegalStateException.class, () -> expired.update(PLAN, 12L));
    assertThrows(IllegalStateException.class,
        () -> expired.returning(PLAN, (rows, context) -> rows.getLong(1), 1, 12L));
    assertEquals(1, scenario.executed); assertEquals(1, scenario.prepared);
    assertEquals(1, scenario.closed); assertEquals(1, scenario.released);
  }

  @Test void ownsOneConnectionAndBindsEveryChunkWithoutTransactionChanges() {
    Scenario scenario = new Scenario();
    var executor = new JdbcExecutor(scenario.provider());
    int total = executor.executeBatchWork(PLAN, ExecutionContext.EMPTY, null, connection -> {
      var first = connection.addBatch(PLAN, List.of(11L, 12L));
      var second = connection.addBatch(PLAN, List.of(13L));
      assertArrayEquals(new int[] {1, 1}, first.counts());
      assertArrayEquals(new int[] {1}, second.counts());
      assertNull(first.failure()); assertNull(second.failure()); return 3;
    });
    assertEquals(3, total); assertEquals(1, scenario.acquired); assertEquals(1, scenario.released);
    assertEquals(1, scenario.prepared); assertEquals(1, scenario.closed);
    assertEquals(2, scenario.configured); assertEquals(2, scenario.clearedParameters);
    assertEquals(List.of(11L, 12L, 13L), scenario.bound);
  }
  @Test void retainsOriginalPartialCountsSqlStateAndStatementCloseSuppression() {
    Scenario scenario = new Scenario();
    scenario.failure = new BatchUpdateException("unsafe driver detail", "23505", 42, new int[] {1, Statement.EXECUTE_FAILED});
    scenario.closeFailure = new SQLException("close failed", "08006");
    var executor = new JdbcExecutor(scenario.provider());
    JdbcBatchOutcome outcome = executor.executeBatchWork(PLAN, ExecutionContext.EMPTY, null,
        connection -> connection.addBatch(PLAN, List.of(11L, 12L, 13L)));
    assertTrue(outcome.executed()); assertEquals(3, outcome.submitted());
    assertArrayEquals(new int[] {1, Statement.EXECUTE_FAILED}, outcome.counts());
    var failure = assertInstanceOf(JdbcExecutionException.class, outcome.failure());
    assertSame(scenario.failure, failure.getCause());
    assertEquals(1, scenario.failure.getSuppressed().length);
    assertEquals(1, scenario.released); assertEquals(1, scenario.closed);
  }
  @Test void missingDriverFailureCountsKeepSubmittedRowsUnknownAndPreserveTheCause() {
    Scenario scenario = new Scenario();
    scenario.failure = new BatchUpdateException("unsafe driver detail", "23505", 42, (int[]) null);
    var executor = new JdbcExecutor(scenario.provider());
    var outcome = executor.executeBatchWork(PLAN, ExecutionContext.EMPTY, null,
        connection -> connection.addBatch(PLAN, List.of(11L, 12L, 13L)));
    assertTrue(outcome.executed()); assertEquals(3, outcome.submitted());
    assertEquals(-1, outcome.failedInput()); assertArrayEquals(new int[0], outcome.counts());
    var failure = assertInstanceOf(JdbcExecutionException.class, outcome.failure());
    assertEquals(JdbcPhase.EXECUTE, failure.phase());
    assertSame(scenario.failure, failure.getCause());
    assertEquals(1, scenario.executed); assertEquals(1, scenario.closed); assertEquals(1, scenario.released);
  }
  @Test void bindingFailureNeverExecutesQueuedRowsAndRetainsItsPhase() {
    Scenario scenario = new Scenario(); scenario.failValue = 12L;
    var executor = new JdbcExecutor(scenario.provider());
    var outcome = executor.executeBatchWork(PLAN, ExecutionContext.EMPTY, null,
        connection -> connection.addBatch(PLAN, List.of(11L, 12L, 13L)));
    assertFalse(outcome.executed()); assertEquals(1, outcome.submitted()); assertEquals(1, outcome.failedInput());
    assertEquals(JdbcPhase.BIND, assertInstanceOf(JdbcExecutionException.class, outcome.failure()).phase());
    assertEquals(0, scenario.executed); assertEquals(1, scenario.closed); assertEquals(1, scenario.released);
  }
  @Test void closesThePreviousShapeBeforePreparingAnotherAndReleasesAtScopeExit() {
    Scenario scenario = new Scenario();
    var other = new CompiledMutationPlan<>("insert", "batch-test",
        new RenderedSql("INSERT INTO other_item(id) VALUES (?)", PLAN.renderedSql().parameters()),
        PLAN.parameterBinder());
    var executor = new JdbcExecutor(scenario.provider());
    executor.executeBatchWork(PLAN, ExecutionContext.EMPTY, null, connection -> {
      assertNull(connection.addBatch(PLAN, List.of(11L)).failure());
      assertNull(connection.addBatch(other, List.of(12L)).failure());
      assertEquals(1, scenario.closed);
      assertNull(connection.addBatch(PLAN, List.of(13L)).failure());
      assertEquals(2, scenario.closed);
      return 3;
    });
    assertEquals(3, scenario.prepared); assertEquals(3, scenario.closed);
    assertEquals(1, scenario.acquired); assertEquals(1, scenario.released);
  }
  @Test void bindingFailureOnAReusedStatementKeepsItsIndexAndStopsBeforeExecution() {
    Scenario scenario = new Scenario(); scenario.failValue = 12L;
    var executor = new JdbcExecutor(scenario.provider());
    var failed = executor.executeBatchWork(PLAN, ExecutionContext.EMPTY, null, connection -> {
      assertNull(connection.addBatch(PLAN, List.of(11L)).failure());
      return connection.addBatch(PLAN, List.of(12L, 13L));
    });
    assertFalse(failed.executed()); assertEquals(0, failed.submitted()); assertEquals(0, failed.failedInput());
    assertEquals(JdbcPhase.BIND, assertInstanceOf(JdbcExecutionException.class, failed.failure()).phase());
    assertEquals(1, scenario.executed); assertEquals(1, scenario.prepared); assertEquals(1, scenario.closed);
    assertEquals(List.of(11L), scenario.bound); assertEquals(1, scenario.released);
  }
  @Test void scopeCloseFailureRetainsItsPhaseAndReleasesTheConnection() {
    Scenario scenario = new Scenario(); scenario.closeFailure = new SQLException("close failed", "08006");
    var executor = new JdbcExecutor(scenario.provider());
    var failure = assertThrows(JdbcExecutionException.class, () ->
        executor.executeBatchWork(PLAN, ExecutionContext.EMPTY, null,
            connection -> connection.addBatch(PLAN, List.of(11L))));
    assertEquals(JdbcPhase.STATEMENT_CLOSE, failure.phase());
    assertSame(scenario.closeFailure, failure.getCause());
    assertEquals(1, scenario.closed); assertEquals(1, scenario.released);
  }
  @Test void rechecksProviderConfigurationBeforeReusingAStatement() {
    Scenario scenario = new Scenario(); scenario.failConfiguration = 2;
    var executor = new JdbcExecutor(scenario.provider());
    var failed = executor.executeBatchWork(PLAN, ExecutionContext.EMPTY, null, connection -> {
      assertNull(connection.addBatch(PLAN, List.of(11L)).failure());
      return connection.addBatch(PLAN, List.of(12L));
    });
    assertFalse(failed.executed()); assertEquals(0, failed.submitted());
    assertEquals(JdbcPhase.CONFIGURE, assertInstanceOf(JdbcExecutionException.class, failed.failure()).phase());
    assertEquals(2, scenario.configured); assertEquals(1, scenario.executed);
    assertEquals(1, scenario.prepared); assertEquals(1, scenario.closed); assertEquals(1, scenario.released);
  }
  @Test void closesCachedStatementsWhenPreparingTheNextChunkFails() {
    Scenario scenario = new Scenario(); scenario.closeFailure = new SQLException("close failed", "08006");
    var executor = new JdbcExecutor(scenario.provider());
    var preparationFailure = new IllegalStateException("next chunk preparation failed");
    var failure = assertThrows(IllegalStateException.class, () ->
        executor.executeBatchWork(PLAN, ExecutionContext.EMPTY, null, connection -> {
          assertNull(connection.addBatch(PLAN, List.of(11L)).failure());
          throw preparationFailure;
        }));
    assertSame(preparationFailure, failure);
    assertArrayEquals(new Throwable[] {scenario.closeFailure}, failure.getSuppressed());
    assertEquals(1, scenario.executed); assertEquals(1, scenario.closed); assertEquals(1, scenario.released);
  }
  private static final class Scenario {
    int acquired, released, prepared, closed, executed, configured, clearedParameters;
    int failConfiguration;
    final List<Long> bound = new ArrayList<>();
    Long failValue;
    BatchUpdateException failure;
    SQLException closeFailure;
    ConnectionProvider provider() {
      Connection connection = proxy(Connection.class, (ignored, method, arguments) -> {
        if (!method.getName().equals("prepareStatement")) throw new AssertionError("unexpected connection action: " + method.getName());
        assertEquals(1, arguments.length, "batch keys must remain disabled"); prepared++;
        int[] queued = {0};
        return proxy(PreparedStatement.class, (statement, action, args) -> switch (action.getName()) {
          case "setLong" -> { Long value = (Long) args[1]; if (Objects.equals(failValue, value)) throw new SQLException("bind failed", "07000"); bound.add(value); yield null; }
          case "addBatch" -> { queued[0]++; yield null; }
          case "executeBatch" -> { executed++; if (failure != null) throw failure; int[] counts = new int[queued[0]]; Arrays.fill(counts, 1); yield counts; }
          case "clearBatch" -> { queued[0] = 0; yield null; }
          case "clearParameters" -> { clearedParameters++; yield null; }
          case "close" -> { closed++; if (closeFailure != null) throw closeFailure; yield null; }
          default -> throw new AssertionError(action.getName());
        });
      });
      return new ConnectionProvider() {
        @Override public Connection acquire(ExecutionContext context) { acquired++; return connection; }
        @Override public void release(Connection acquired, ExecutionContext context) { released++; }
        @Override public void configureStatement(PreparedStatement statement, ExecutionContext context, int timeout) throws SQLException {
          if (++configured == failConfiguration) throw new SQLException("transaction deadline expired", "57014");
        }
      };
    }
  }
  @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }
}
