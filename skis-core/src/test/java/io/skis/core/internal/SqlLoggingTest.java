package io.skis.core.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.SkisConfigurationException;
import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisStatementKind;
import io.skis.core.SqlLoggingOptions;
import io.skis.core.spi.*;
import io.skis.testlogging.TestLoggerProvider;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqlLoggingTest {
  @BeforeEach void reset() { TestLoggerProvider.reset(); }
  @AfterEach void clear() { TestLoggerProvider.reset(); }

  @Test void defaultsAndParameterOnlyConfigurationLeaveObservationDisabled() {
    assertFalse(SqlLoggingOptions.NONE.sql());
    assertFalse(SqlLoggingOptions.NONE.parameters());
    assertNull(SqlLoggingOptions.NONE.slowQueryThreshold());
    assertSame(ExecutionListenerDispatcher.NONE,
        ExecutionListenerDispatcher.of(List.of(), SqlLoggingOptions.NONE));
    assertFalse(ExecutionListenerDispatcher.of(List.of(), SqlLoggingOptions.builder()
        .parameters(true).build()).enabled());
    var configured = ExecutionListenerDispatcher.of(List.of(), SqlLoggingOptions.builder()
        .sql(true).slowQueryThreshold(Duration.ofSeconds(1)).build());
    assertFalse(configured.enabled());
    TestLoggerProvider.failProbe = true;
    assertFalse(configured.enabled());
    assertThrows(SkisConfigurationException.class,
        () -> SqlLoggingOptions.builder().slowQueryThreshold(Duration.ZERO));
    assertThrows(SkisConfigurationException.class,
        () -> SqlLoggingOptions.builder().slowQueryThreshold(Duration.ofNanos(-1)));
  }

  @Test void userListenersRemainActiveWhenLogsAreOffAndOrdinaryFailuresAreIsolated() {
    AtomicInteger completed = new AtomicInteger();
    ExecutionListener broken = new ExecutionListener() {
      @Override public void statementCompleted(StatementCompletedEvent event) {
        throw new IllegalArgumentException("secret listener and driver message");
      }
    };
    ExecutionListener good = new ExecutionListener() {
      @Override public void statementCompleted(StatementCompletedEvent event) {
        completed.incrementAndGet();
      }
    };
    TestLoggerProvider.WARN.add("io.skis.internal");
    var dispatcher = ExecutionListenerDispatcher.of(List.of(broken, good), SqlLoggingOptions.NONE);
    finish(dispatcher);
    assertEquals(1, completed.get());
    assertEquals(1, TestLoggerProvider.EVENTS.size());
    assertFalse(TestLoggerProvider.EVENTS.getFirst().message().contains("secret"));
    TestLoggerProvider.failure = 1;
    assertDoesNotThrow(() -> finish(dispatcher));
    assertEquals(2, completed.get());
  }

  @Test void operationEntryCapturesEachLevelOnceAndSkipsUnobservedIds() {
    var dispatcher = ExecutionListenerDispatcher.of(List.of(), SqlLoggingOptions.builder()
        .sql(true).slowQueryThreshold(Duration.ofSeconds(1)).build());
    for (int demand = 0; demand < 4; demand++) {
      TestLoggerProvider.reset();
      if ((demand & 1) != 0) TestLoggerProvider.DEBUG.add("io.skis.sql");
      if ((demand & 2) != 0) TestLoggerProvider.WARN.add("io.skis.sql.slow");
      AtomicInteger sqlProbes = new AtomicInteger();
      AtomicInteger slowProbes = new AtomicInteger();
      TestLoggerProvider.onProbe = (logger, level) -> {
        if (logger.equals("io.skis.sql")) sqlProbes.incrementAndGet();
        else if (logger.equals("io.skis.sql.slow")) slowProbes.incrementAndGet();
        else fail("unexpected logger probe: " + logger);
      };
      var operation = dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, "test");
      assertEquals(1, sqlProbes.get());
      assertEquals(1, slowProbes.get());
      if (demand == 0) {
        assertNull(operation);
      } else {
        var observed = Objects.requireNonNull(operation);
        assertEquals(demand, observed.operationId(), "disabled operation consumed an ID");
        observed.completeSucceeded(ExecutionTermination.COMPLETED);
      }
    }
    TestLoggerProvider.onProbe = (logger, level) -> fail("disabled logging probed a provider");
    assertNull(ExecutionListenerDispatcher.NONE.startOperation(ExecutionOperationKind.FETCH_LIST, "test"));
    assertNull(ExecutionListenerDispatcher.of(List.of(), SqlLoggingOptions.builder()
        .parameters(true).build()).startOperation(ExecutionOperationKind.FETCH_LIST, "test"));
    assertTrue(TestLoggerProvider.EVENTS.isEmpty());
  }

  @Test void sqlAndSlowLogUseValueFreeEventsAndSnapshotDemandForTheOperation() throws Exception {
    TestLoggerProvider.DEBUG.add("io.skis.sql");
    TestLoggerProvider.WARN.add("io.skis.sql.slow");
    var dispatcher = ExecutionListenerDispatcher.of(List.of(), SqlLoggingOptions.builder()
        .sql(true).slowQueryThreshold(Duration.ofNanos(1)).build());
    assertTrue(dispatcher.enabled());
    var operation = Objects.requireNonNull(
        dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, "test"));
    var statement = startElapsedStatement(operation, "SELECT ?\n");
    statement.completeFailed(new IllegalArgumentException("secret driver cause"),
        SkisDiagnosticStage.EXECUTION, 0, -1);
    operation.completeSucceeded(ExecutionTermination.COMPLETED);
    assertEquals(3, TestLoggerProvider.EVENTS.size());
    String output = TestLoggerProvider.EVENTS.toString();
    assertTrue(output.contains("SELECT ?\\n"));
    assertFalse(output.contains("secret"));
    assertFalse(output.contains("parameters="));
    // Initially disabled SQL logging must not appear midway through a user-observed operation.
    TestLoggerProvider.reset();
    var users = ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {}),
        SqlLoggingOptions.builder().sql(true).build());
    var initiallyOff = Objects.requireNonNull(
        users.startOperation(ExecutionOperationKind.FETCH_LIST, "test"));
    TestLoggerProvider.DEBUG.add("io.skis.sql");
    initiallyOff.startStatement(StatementPurpose.PRIMARY, SkisStatementKind.SELECT,
        "test", "fingerprint", "SELECT ?")
        .completeSucceeded(ExecutionTermination.COMPLETED, 0, -1);
    assertTrue(TestLoggerProvider.EVENTS.isEmpty());
  }

  @Test void ordinaryBackendFailuresAreDroppedAndFatalErrorsPropagate() {
    TestLoggerProvider.DEBUG.add("io.skis.sql");
    var dispatcher = ExecutionListenerDispatcher.of(List.of(),
        SqlLoggingOptions.builder().sql(true).build());
    TestLoggerProvider.failure = 1;
    assertDoesNotThrow(() -> finish(dispatcher));
    TestLoggerProvider.failure = 2;
    assertThrows(AssertionError.class, () -> finish(dispatcher));
  }

  @Test void loggingBackendsCannotReenterAndAlwaysReleaseTheirCallbackScope() {
    TestLoggerProvider.DEBUG.add("io.skis.sql");
    var dispatcher = ExecutionListenerDispatcher.of(List.of(),
        SqlLoggingOptions.builder().sql(true).build());
    AtomicInteger rejected = new AtomicInteger();
    TestLoggerProvider.onLog = () -> {
      assertThrows(IllegalStateException.class, ExecutionListenerDispatcher::rejectReentrantCall);
      rejected.incrementAndGet();
    };
    for (int failure : List.of(0, 1, 2)) {
      TestLoggerProvider.failure = failure;
      if (failure == 2) {
        assertThrows(AssertionError.class, () -> finish(dispatcher));
      } else {
        assertDoesNotThrow(() -> finish(dispatcher));
      }
      assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
    }
    assertEquals(5, rejected.get());
  }

  @Test void levelProbesRejectReentryAndReleaseTheirScopeOnEveryExit() {
    var logger = org.slf4j.LoggerFactory.getLogger("io.skis.sql");
    AtomicInteger rejected = new AtomicInteger();
    for (boolean enabled : List.of(false, true)) {
      for (int failure : List.of(0, 1, 2)) {
        TestLoggerProvider.reset();
        if (enabled) {
          TestLoggerProvider.DEBUG.add("io.skis.sql");
          TestLoggerProvider.WARN.add("io.skis.sql");
        }
        TestLoggerProvider.onProbe = (name, level) -> {
          assertThrows(IllegalStateException.class,
              () -> ExecutionListenerDispatcher.NONE.startOperation(ExecutionOperationKind.FETCH_LIST, "test"));
          rejected.incrementAndGet();
          if (failure == 1) throw new IllegalStateException("secret probe failure");
          if (failure == 2) throw new AssertionError("fatal probe failure");
        };
        if (failure == 2) {
          assertThrows(AssertionError.class, () -> SafeLog.debugEnabled(logger));
          assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
          assertThrows(AssertionError.class, () -> SafeLog.warnEnabled(logger));
        } else {
          assertEquals(enabled && failure == 0, SafeLog.debugEnabled(logger));
          assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
          assertEquals(enabled && failure == 0, SafeLog.warnEnabled(logger));
        }
        assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
        assertTrue(TestLoggerProvider.EVENTS.isEmpty());
      }
    }
    assertEquals(12, rejected.get());
  }

  @Test void nestedSafetyLogsPreserveTheEnclosingListenerGuard() {
    TestLoggerProvider.WARN.add("io.skis.internal");
    AtomicInteger callbacks = new AtomicInteger();
    var dispatcher = ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {
      @Override public void statementStarted(StatementStartedEvent event) {
        SafeLog.debugEnabled(org.slf4j.LoggerFactory.getLogger("io.skis.internal"));
        assertThrows(IllegalStateException.class, ExecutionListenerDispatcher::rejectReentrantCall);
        SafeLog.warnEnabled(org.slf4j.LoggerFactory.getLogger("io.skis.internal"));
        assertThrows(IllegalStateException.class, ExecutionListenerDispatcher::rejectReentrantCall);
        SafeLog.warn(org.slf4j.LoggerFactory.getLogger("io.skis.internal"), "safe warning");
        assertThrows(IllegalStateException.class, ExecutionListenerDispatcher::rejectReentrantCall);
        callbacks.incrementAndGet();
      }
    }));
    finish(dispatcher);
    assertEquals(1, callbacks.get());
    assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
  }

  @Test
  @org.junit.jupiter.api.Timeout(10)
  void aDisabledLevelProbeGuardsOnlyItsOwnThread() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    TestLoggerProvider.onProbe = (name, level) -> {
      assertThrows(IllegalStateException.class, ExecutionListenerDispatcher::rejectReentrantCall);
      entered.countDown();
      try {
        assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new AssertionError(failure);
      }
      assertThrows(IllegalStateException.class, ExecutionListenerDispatcher::rejectReentrantCall);
    };
    var logger = org.slf4j.LoggerFactory.getLogger("io.skis.sql");
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var probe = workers.submit(() -> {
        assertFalse(SafeLog.debugEnabled(logger));
        assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
      });
      try {
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
        assertNull(ExecutionListenerDispatcher.NONE.startOperation(
            ExecutionOperationKind.FETCH_LIST, "test"));
      } finally {
        release.countDown();
      }
      probe.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertTrue(TestLoggerProvider.EVENTS.isEmpty());
  }

  @Test void slowOnlyLoggingIncludesAnEscapedBoundedSqlTemplate() {
    TestLoggerProvider.WARN.add("io.skis.sql.slow");
    var dispatcher = ExecutionListenerDispatcher.of(List.of(), SqlLoggingOptions.builder()
        .slowQueryThreshold(Duration.ofNanos(1)).build());
    var operation = Objects.requireNonNull(
        dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, "test"));
    String template = "SELECT ?\n" + "😀".repeat(5000);
    var statement = new StatementObservation(operation, dispatcher, dispatcher.nextId(),
        StatementPurpose.PRIMARY, SkisStatementKind.SELECT, "test", "fingerprint", template,
        System.nanoTime() - Duration.ofSeconds(1).toNanos());
    statement.completeSucceeded(ExecutionTermination.COMPLETED, 0, -1);
    operation.completeSucceeded(ExecutionTermination.COMPLETED);
    assertEquals(1, TestLoggerProvider.EVENTS.size());
    var event = TestLoggerProvider.EVENTS.getFirst();
    assertEquals("io.skis.sql.slow", event.logger());
    String sql = event.message().substring(event.message().indexOf(" sql=") + 5);
    assertTrue(sql.startsWith("SELECT ?\\n"));
    assertEquals(4096, sql.codePointCount(0, sql.length()));
    assertTrue(sql.endsWith("[truncated]"));
    assertFalse(sql.contains("\n"));
    assertFalse(Character.isHighSurrogate(sql.charAt(sql.length() - 1)));
  }

  @Test void parameterLoggingIsExcludedFromBothStatementAndOperationDurations() {
    TestLoggerProvider.WARN.add("io.skis.sql.slow");
    var statements = new java.util.ArrayList<StatementCompletedEvent>();
    var operations = new java.util.ArrayList<OperationCompletedEvent>();
    var dispatcher = ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {
      @Override public void statementCompleted(StatementCompletedEvent event) { statements.add(event); }
      @Override public void operationCompleted(OperationCompletedEvent event) { operations.add(event); }
    }), SqlLoggingOptions.builder().slowQueryThreshold(Duration.ofHours(1)).build());
    long loggingNanos = Duration.ofDays(1).toNanos();
    long startedNanos = System.nanoTime() - loggingNanos;
    var operation = new OperationObservation(dispatcher, dispatcher.nextId(),
        ExecutionOperationKind.FETCH_LIST, "test", startedNanos);
    var statement = new StatementObservation(operation, dispatcher, dispatcher.nextId(),
        StatementPurpose.PRIMARY, SkisStatementKind.SELECT, "test", "fingerprint", "SELECT ?",
        startedNanos);
    statement.excludeLoggingNanos(loggingNanos);
    statement.completeSucceeded(ExecutionTermination.COMPLETED, 0, -1);
    operation.completeSucceeded(ExecutionTermination.COMPLETED);
    assertEquals(1, statements.size());
    assertEquals(1, operations.size());
    assertTrue(statements.getFirst().durationNanos() < loggingNanos);
    assertTrue(operations.getFirst().durationNanos() < loggingNanos);
    assertTrue(TestLoggerProvider.EVENTS.isEmpty(), "logging time triggered a slow-query warning");
  }

  @Test void reusesLoggingSnapshotsAcrossOverlappingOperationsAndLevelChanges() throws Exception {
    AtomicInteger completed = new AtomicInteger();
    var dispatcher = ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {
      @Override public void operationCompleted(OperationCompletedEvent event) {
        completed.incrementAndGet();
      }
    }), SqlLoggingOptions.builder().sql(true).slowQueryThreshold(Duration.ofNanos(1)).build());
    var dispatcherField = OperationObservation.class.getDeclaredField("dispatcher");
    dispatcherField.setAccessible(true);
    var snapshots = new java.util.HashSet<Object>();
    var ids = new java.util.HashSet<Long>();
    for (int demand = 0; demand < 4; demand++) {
      TestLoggerProvider.DEBUG.clear();
      TestLoggerProvider.WARN.clear();
      if ((demand & 1) != 0) TestLoggerProvider.DEBUG.add("io.skis.sql");
      if ((demand & 2) != 0) TestLoggerProvider.WARN.add("io.skis.sql.slow");
      var first = Objects.requireNonNull(
          dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, "test"));
      var second = Objects.requireNonNull(
          dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, "test"));
      assertSame(dispatcherField.get(first), dispatcherField.get(second));
      snapshots.add(dispatcherField.get(first));
      assertTrue(ids.add(first.operationId()));
      assertTrue(ids.add(second.operationId()));
      startElapsedStatement(first, "SELECT ?")
          .completeSucceeded(ExecutionTermination.COMPLETED, 0, -1);
      startElapsedStatement(second, "SELECT ?")
          .completeSucceeded(ExecutionTermination.COMPLETED, 0, -1);
      second.completeSucceeded(ExecutionTermination.COMPLETED);
      first.completeSucceeded(ExecutionTermination.COMPLETED);
    }
    assertEquals(4, snapshots.size());
    assertEquals(8, completed.get());
    assertEquals(12, TestLoggerProvider.EVENTS.size());
  }

  @Test void slowLoggingIncludesTheThresholdAndRejectsShorterDurations() {
    TestLoggerProvider.WARN.add("io.skis.sql.slow");
    var logging = new SqlLoggingListener(SqlLoggingOptions.builder()
        .slowQueryThreshold(Duration.ofNanos(1000)).build()).snapshot(SqlLoggingListener.SLOW_DEMAND);
    for (long duration : List.of(0L, 999L, 1000L, 1001L)) {
      TestLoggerProvider.EVENTS.clear();
      logging.statementCompleted(new StatementCompletedEvent(1L, 2L,
          ExecutionOperationKind.FETCH_LIST, StatementPurpose.PRIMARY, SkisStatementKind.SELECT,
          "test", "fingerprint", "SELECT ?", ExecutionOutcome.SUCCEEDED,
          ExecutionTermination.COMPLETED, duration, -1L, 0L, -1L, null, null));
      assertEquals(duration >= 1000L ? 1 : 0, TestLoggerProvider.EVENTS.size());
    }
  }

  @Test void escapesControlsAndBoundsUnicodeWithoutSplittingPairs() {
    assertEquals("a\\nb\\r\\t\\u0000", SafeLog.text("a\nb\r\t\0", 128));
    String bounded = SafeLog.text("😀".repeat(200), 128);
    assertEquals(128, bounded.codePointCount(0, bounded.length()));
    assertTrue(bounded.endsWith("[truncated]"));
    assertFalse(Character.isHighSurrogate(bounded.charAt(bounded.length() - 1)));
    assertEquals("\\u2028\\u2029\\u2066\\ud800X\\udc00",
        SafeLog.text("\u2028\u2029\u2066\uD800X\uDC00", 128));
    assertEquals("", SafeLog.text("", 128));
    String ascii = "java.lang.String";
    String unicode = "type😀名称";
    assertSame(ascii, SafeLog.text(ascii, ascii.length()));
    assertSame(unicode, SafeLog.text(unicode, unicode.codePointCount(0, unicode.length())));
    assertEquals("type😀\\n", SafeLog.text("type😀\n", 7));
    assertEquals("type😀\\u2066", SafeLog.text("type😀\u2066", 11));
    assertEquals("type😀[truncated]", SafeLog.text("type😀" + "x".repeat(20), 16));
    assertEquals("", SafeLog.text("😀", 0));
  }

  @Test void configurationIsAnImmutableSnapshotAndHugeThresholdsStayExplicit() {
    var builder = SqlLoggingOptions.builder().sql(true).parameters(true);
    var first = builder.build();
    builder.sql(false).parameters(false);
    assertTrue(first.sql());
    assertTrue(first.parameters());
    assertFalse(builder.build().sql());
    TestLoggerProvider.WARN.add("io.skis.sql.slow");
    var dispatcher = ExecutionListenerDispatcher.of(List.of(), SqlLoggingOptions.builder()
        .slowQueryThreshold(Duration.ofSeconds(Long.MAX_VALUE)).build());
    assertTrue(dispatcher.enabled());
    finish(dispatcher);
    assertTrue(TestLoggerProvider.EVENTS.isEmpty());
  }

  private static void finish(ExecutionListenerDispatcher dispatcher) {
    var operation = Objects.requireNonNull(
        dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, "test"));
    operation.startStatement(StatementPurpose.PRIMARY, SkisStatementKind.SELECT,
        "test", "fingerprint", "SELECT ?")
        .completeSucceeded(ExecutionTermination.COMPLETED, 0, -1);
    operation.completeSucceeded(ExecutionTermination.COMPLETED);
  }

  // Use the captured demand and an explicit start time so event counts do not depend on clock granularity.
  private static StatementObservation startElapsedStatement(OperationObservation operation, String sql)
      throws ReflectiveOperationException {
    var field = OperationObservation.class.getDeclaredField("dispatcher");
    field.setAccessible(true);
    var captured = (ExecutionListenerDispatcher) field.get(operation);
    long statementId = captured.nextId();
    var statement = new StatementObservation(operation, captured, statementId,
        StatementPurpose.PRIMARY, SkisStatementKind.SELECT, "test", "fingerprint", sql,
        System.nanoTime() - Duration.ofSeconds(1).toNanos());
    captured.statementStarted(new StatementStartedEvent(operation.operationId(), statementId,
        operation.operationKind(), StatementPurpose.PRIMARY, SkisStatementKind.SELECT,
        "test", "fingerprint", sql));
    return statement;
  }
}
