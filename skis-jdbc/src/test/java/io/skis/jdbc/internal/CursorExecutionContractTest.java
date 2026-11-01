package io.skis.jdbc.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.ExecutionOptions;
import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.TransactionException;
import io.skis.core.spi.ExecutionContext;
import io.skis.dialect.spi.CursorReadSupport;
import io.skis.dialect.spi.RenderedSql;
import io.skis.dialect.spi.SqlExceptionCategory;
import io.skis.jdbc.QueryExecutionException;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.RowReadContext;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class CursorExecutionContractTest {
  private static final CursorReadSupport SUPPORT = new CursorReadSupport() {
    @Override public int defaultFetchSize() { return 256; }
    @Override public boolean supportsConnection(Connection connection) { return true; }
  };

  @Test
  void cleanerDoesNotRetainAnAbandonedTransactionOrItsCursor() {
    List<WeakReference<?>> references = abandonedTransaction();
    for (int attempt = 0; attempt < 120
        && references.stream().anyMatch(reference -> reference.get() != null); attempt++) {
      byte[][] pressure = new byte[8][];
      for (int index = 0; index < pressure.length; index++) {
        pressure[index] = new byte[256 * 1024];
      }
      System.gc();
      Reference.reachabilityFence(pressure);
      LockSupport.parkNanos(5_000_000L);
    }
    for (WeakReference<?> reference : references) {
      assertNull(reference.get(), "Cleaner retained the abandoned transaction/resource graph");
    }
  }

  private static List<WeakReference<?>> abandonedTransaction() {
    // Deliberately abandon only fake JDBC resources; a Cleaner must diagnose, never own, this graph.
    Driver driver = new Driver();
    JdbcTransaction transaction = JdbcTransaction.beginWithExecutor(
        executor(driver, ExecutionOptions.NONE), ExecutionContext.EMPTY);
    JdbcExecutor jdbc = transaction.jdbcExecutor();
    JdbcCursor<String> cursor = jdbc.openCursor(plan(SUPPORT), null, ExecutionContext.EMPTY);
    assertTrue(cursor.advance());
    return List.of(new WeakReference<>(transaction), new WeakReference<>(jdbc),
        new WeakReference<>(cursor), new WeakReference<>(driver));
  }

  @Test
  void deregistrationUsesIdentityAndPreservesReverseOrderAfterArbitraryEarlyCloses()
      throws Exception {
    for (boolean commit : List.of(false, true)) {
      Driver driver = new Driver();
      List<Integer> closed = new ArrayList<>();
      AtomicInteger comparisons = new AtomicInteger();
      try (JdbcTransaction transaction = JdbcTransaction.beginWithExecutor(
          executor(driver, ExecutionOptions.NONE), ExecutionContext.EMPTY)) {
        ConnectionProvider provider = transaction.jdbcExecutor().connectionProvider();
        List<AutoCloseable> resources = new ArrayList<>();
        List<Runnable> removals = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
          int id = index;
          AtomicReference<Runnable> removal = new AtomicReference<>();
          AutoCloseable resource = proxy(AutoCloseable.class, (object, method, args) ->
              switch (method.getName()) {
                case "close" -> {
                  closed.add(id);
                  Runnable action = removal.get();
                  if (action != null) action.run();
                  yield null;
                }
                case "equals" -> {
                  comparisons.incrementAndGet();
                  yield object == args[0];
                }
                case "hashCode" -> System.identityHashCode(object);
                default -> throw new AssertionError(method.getName());
              });
          Runnable registration = provider.registerCursor(driver.connection, resource,
              ExecutionContext.EMPTY);
          assertNotNull(registration);
          removal.set(registration);
          resources.add(resource);
          removals.add(registration);
        }
        for (int index : List.of(0, 4, 7)) {
          resources.get(index).close();
          removals.get(index).run();
        }
        if (commit) transaction.commit(); else transaction.rollback();
        assertEquals(List.of(0, 4, 7, 6, 5, 3, 2, 1), closed);
        assertEquals(0, comparisons.get(), "deregistration must not search the active resources");
        removals.forEach(Runnable::run);
      }
      assertEquals(8, closed.size());
      assertEquals(1, driver.releases);
    }
  }

  @Test
  void cursorDefaultsAndFieldOverridesDoNotChangeMaterializedReads() {
    Driver driver = new Driver();
    JdbcExecutor executor = executor(driver, ExecutionOptions.NONE);
    executor.fetchList(plan(SUPPORT), null);
    assertEquals(0, driver.fetchSize);
    try (var cursor = executor.openCursor(plan(SUPPORT), null, ExecutionContext.EMPTY)) {
      assertEquals(256, driver.fetchSize);
      assertEquals(List.of(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
          ResultSet.CLOSE_CURSORS_AT_COMMIT), driver.prepareFlags);
    }
    executor = executor(driver, ExecutionOptions.builder().fetchSize(32).maxRows(11).build())
        .withDefaultExecutionOptions(ExecutionOptions.builder().fetchSize(16).build());
    try (var cursor = executor.openCursor(plan(SUPPORT), null, ExecutionContext.EMPTY)) {
      assertEquals(16, driver.fetchSize);
      assertEquals(11, driver.maxRows);
    }
    try (var cursor = executor.openCursor(plan(SUPPORT), null,
        ExecutionContext.of(ExecutionOptions.builder().fetchSize(2).build()))) {
      assertEquals(2, driver.fetchSize);
      assertEquals(11, driver.maxRows);
    }
    assertEquals(0, driver.autoCommitChanges);
    assertEquals(0, driver.commits);
  }

  @Test
  void unsupportedDialectsAndExplicitZeroFailBeforeBorrowing() {
    Driver driver = new Driver();
    JdbcExecutor executor = executor(driver, ExecutionOptions.NONE);
    assertCode(SkisErrorCode.QUERY_STREAMING_UNSUPPORTED,
        () -> executor.openCursor(plan(CursorReadSupport.UNSUPPORTED), null, ExecutionContext.EMPTY));
    assertCode(SkisErrorCode.QUERY_STREAMING_PRECONDITION,
        () -> executor.openCursor(plan(SUPPORT), null,
            ExecutionContext.of(ExecutionOptions.builder().fetchSize(0).build())));
    assertCode(SkisErrorCode.QUERY_STREAMING_PRECONDITION,
        () -> executor(driver, ExecutionOptions.builder().fetchSize(0).build())
            .openCursor(plan(SUPPORT), null, ExecutionContext.EMPTY));
    assertEquals(0, driver.acquires);
    try (var cursor = executor(driver, ExecutionOptions.builder().fetchSize(0).build())
        .openCursor(plan(SUPPORT), null,
            ExecutionContext.of(ExecutionOptions.builder().fetchSize(2).build()))) {
      assertEquals(2, driver.fetchSize);
    }
    executor.fetchList(plan(SUPPORT), null,
        ExecutionContext.of(ExecutionOptions.builder().fetchSize(0).build()));
    assertEquals(0, driver.fetchSize);
  }

  @Test
  void rejectsUnconfirmedSqlOnceAtThePlanBoundary() {
    Driver driver = new Driver();
    var multiple = new CompiledQueryPlan<String, Object>("fake",
        new RenderedSql("SELECT 1; SELECT 2", List.of()),
        (statement, index, value, context) -> index, (result, context) -> "row", SUPPORT);
    assertFalse(multiple.singleStatement());
    assertCode(SkisErrorCode.QUERY_STREAMING_PRECONDITION,
        () -> executor(driver, ExecutionOptions.NONE).openCursor(multiple, null, ExecutionContext.EMPTY));
    assertEquals(0, driver.acquires);
  }

  @Test
  void transactionAndActualStatementPreconditionsFailWithoutExecutingOrChangingOwnership() {
    for (String invalid : List.of("autoCommit", "driver", "type", "concurrency", "holdability", "fetch")) {
      Driver driver = new Driver();
      driver.autoCommit = invalid.equals("autoCommit");
      driver.invalidSetting = invalid;
      CursorReadSupport support = invalid.equals("driver") ? new CursorReadSupport() {
        @Override public int defaultFetchSize() { return 256; }
        @Override public boolean supportsConnection(Connection connection) { return false; }
      } : SUPPORT;
      assertCode(SkisErrorCode.QUERY_STREAMING_PRECONDITION,
          () -> executor(driver, ExecutionOptions.NONE)
              .openCursor(plan(support), null, ExecutionContext.EMPTY));
      assertEquals(0, driver.executes, invalid);
      assertEquals(1, driver.releases, invalid);
      assertEquals(0, driver.autoCommitChanges, invalid);
      assertEquals(0, driver.commits, invalid);
      assertEquals(0, driver.rollbacks, invalid);
    }
  }

  @Test
  void inspectionAndRegistrationFailuresCloseEveryOwnedResourceExactlyOnce() {
    Driver inspection = new Driver();
    inspection.inspectionFailure = new SQLException("private connection details", "08006");
    QueryExecutionException failure = assertThrows(QueryExecutionException.class,
        () -> executor(inspection, ExecutionOptions.NONE)
            .openCursor(plan(SUPPORT), null, ExecutionContext.EMPTY));
    assertSame(inspection.inspectionFailure, failure.getCause());
    assertEquals(1, inspection.releases);
    assertEquals(0, inspection.executes);

    Driver registration = new Driver();
    registration.registrationFailure = new SQLException("cannot register resource", "HY000");
    failure = assertThrows(QueryExecutionException.class,
        () -> executor(registration, ExecutionOptions.NONE)
            .openCursor(plan(SUPPORT), null, ExecutionContext.EMPTY));
    assertSame(registration.registrationFailure, failure.getCause());
    assertEquals(List.of("result", "statement", "release"), registration.events);
  }

  @Test
  void driverCancellationKeepsItsClassificationAndClosesTheCursor() throws Exception {
    Driver driver = new Driver();
    var cursor = executor(driver, ExecutionOptions.NONE)
        .openCursor(plan(SUPPORT), null, ExecutionContext.EMPTY);
    driver.statement.cancel();
    QueryExecutionException failure = assertThrows(QueryExecutionException.class, cursor::advance);
    assertEquals(SkisErrorCode.DB_QUERY_CANCELED, failure.code());
    assertSame(driver.iterationFailure, failure.getCause());
    assertTrue(cursor.isClosed());
    cursor.close();
    assertEquals(List.of("cancel", "result", "statement", "release"), driver.events);
    assertEquals(1, driver.releases);
  }

  @Test
  void transactionCompletionClosesCursorsBeforeCommitRollbackAndPoolReturn() {
    for (String terminal : List.of("commit", "rollback", "close")) {
      Driver driver = new Driver();
      driver.autoCommit = true;
      JdbcTransaction transaction = JdbcTransaction.beginWithExecutor(
          executor(driver, ExecutionOptions.NONE), ExecutionContext.EMPTY);
      var cursor = transaction.jdbcExecutor().openCursor(plan(SUPPORT), null, ExecutionContext.EMPTY);
      assertTrue(cursor.advance());
      driver.events.clear();
      switch (terminal) {
        case "commit" -> transaction.commit();
        case "rollback" -> transaction.rollback();
        default -> transaction.close();
      }
      assertTrue(cursor.isClosed());
      assertFalse(cursor.advance());
      assertThrows(IllegalStateException.class, cursor::current);
      transaction.close();
      cursor.close();
      assertEquals(List.of("result", "statement", terminal.equals("commit") ? "commit" : "rollback",
          "autoCommit:true", "release"), driver.events);
      assertTrue(driver.autoCommit);
      assertEquals(1, driver.releases);
    }
  }

  @Test
  void failedCursorCleanupStillRollsBackAndReturnsTheConnection() {
    Driver driver = new Driver();
    driver.autoCommit = true;
    JdbcTransaction transaction = JdbcTransaction.beginWithExecutor(
        executor(driver, ExecutionOptions.NONE), ExecutionContext.EMPTY);
    var cursor = transaction.jdbcExecutor().openCursor(plan(SUPPORT), null, ExecutionContext.EMPTY);
    driver.closeFailure = new SQLException("close failure", "HY000");
    assertThrows(TransactionException.class, transaction::close);
    assertTrue(cursor.isClosed());
    assertEquals(1, driver.rollbacks);
    assertEquals(1, driver.releases);
    assertTrue(driver.autoCommit);
    transaction.close();
    assertEquals(1, driver.releases);
  }

  private static void assertCode(SkisErrorCode code, Runnable action) {
    assertEquals(code, assertThrows(QueryValidationException.class, action::run).code());
  }

  private static CompiledQueryPlan<String, Object> plan(CursorReadSupport support) {
    return new CompiledQueryPlan<>("fake", new RenderedSql("SELECT value FROM items", List.of()),
        (statement, index, value, context) -> index, (result, context) -> result.getString(1), support);
  }

  private static JdbcExecutor executor(Driver driver, ExecutionOptions defaults) {
    return new JdbcExecutor(driver, JdbcWriteContext.EMPTY, RowReadContext.EMPTY, defaults,
        failure -> "57014".equals(failure.getSQLState())
            ? SqlExceptionCategory.QUERY_CANCELED : SqlExceptionCategory.UNCATEGORIZED);
  }

  private static final class Driver implements ConnectionProvider {
    boolean autoCommit;
    int autoCommitChanges, commits, rollbacks, fetchSize, maxRows, acquires, releases, executes, reads;
    String invalidSetting = "";
    SQLException inspectionFailure, registrationFailure, iterationFailure, closeFailure;
    final List<String> events = new ArrayList<>();
    List<Object> prepareFlags = List.of();
    final ResultSet result = proxy(ResultSet.class, (object, method, args) -> switch (method.getName()) {
      case "next" -> {
        if (iterationFailure != null) throw iterationFailure;
        yield ++reads == 1;
      }
      case "getString" -> "row";
      case "close" -> { events.add("result"); yield null; }
      default -> throw new AssertionError(method.getName());
    });
    final PreparedStatement statement = proxy(PreparedStatement.class,
        (object, method, args) -> switch (method.getName()) {
          case "setFetchSize" -> { fetchSize = (Integer) args[0]; yield null; }
          case "setMaxRows" -> { maxRows = (Integer) args[0]; yield null; }
          case "getFetchSize" -> invalidSetting.equals("fetch") ? 0 : fetchSize;
          case "getResultSetType" -> invalidSetting.equals("type") ? ResultSet.TYPE_SCROLL_INSENSITIVE : ResultSet.TYPE_FORWARD_ONLY;
          case "getResultSetConcurrency" -> invalidSetting.equals("concurrency") ? ResultSet.CONCUR_UPDATABLE : ResultSet.CONCUR_READ_ONLY;
          case "getResultSetHoldability" -> invalidSetting.equals("holdability") ? ResultSet.HOLD_CURSORS_OVER_COMMIT : ResultSet.CLOSE_CURSORS_AT_COMMIT;
          case "executeQuery" -> { executes++; yield result; }
          case "cancel" -> {
            events.add("cancel");
            iterationFailure = new SQLException("private cancellation details", "57014");
            yield null;
          }
          case "close" -> {
            events.add("statement");
            if (closeFailure != null) throw closeFailure;
            yield null;
          }
          default -> throw new AssertionError(method.getName());
        });
    final Connection connection = proxy(Connection.class, (object, method, args) -> switch (method.getName()) {
      case "prepareStatement" -> {
        prepareFlags = java.util.Arrays.asList(args).subList(1, args.length);
        reads = 0;
        yield statement;
      }
      case "getAutoCommit" -> {
        if (inspectionFailure != null) throw inspectionFailure;
        yield autoCommit;
      }
      case "setAutoCommit" -> {
        autoCommit = (Boolean) args[0]; autoCommitChanges++;
        events.add("autoCommit:" + autoCommit); yield null;
      }
      case "commit" -> { commits++; events.add("commit"); yield null; }
      case "rollback" -> { rollbacks++; events.add("rollback"); yield null; }
      default -> throw new AssertionError(method.getName());
    });

    @Override public Connection acquire(ExecutionContext context) { acquires++; return connection; }
    @Override public void release(Connection connection, ExecutionContext context) {
      releases++; events.add("release");
    }
    @Override public Runnable registerCursor(Connection connection, AutoCloseable cursor,
        ExecutionContext context) throws SQLException {
      if (registrationFailure != null) throw registrationFailure;
      return () -> {};
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }
}
