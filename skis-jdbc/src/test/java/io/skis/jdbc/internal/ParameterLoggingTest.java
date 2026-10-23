package io.skis.jdbc.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.ExecutionOptions;
import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SqlLoggingOptions;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.ParameterRedactor;
import io.skis.core.spi.StatementCompletedEvent;
import io.skis.core.spi.StatementPurpose;
import io.skis.core.spi.StatementStartedEvent;
import io.skis.dialect.spi.ExceptionClassifier;
import io.skis.dialect.spi.RenderedSql;
import io.skis.jdbc.JdbcExecutionException;
import io.skis.jdbc.QueryExecutionException;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.ParameterBinder;
import io.skis.mapping.spi.ParameterBindingObserver;
import io.skis.mapping.spi.RowReadContext;
import io.skis.sql.ast.ParameterSlot;
import io.skis.testlogging.TestLoggerProvider;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ParameterLoggingTest {
  private static final String LOGGER = "io.skis.sql.parameters";
  private static final String FINGERPRINT = JdbcFingerprint.of("SELECT ?");
  private static final Object POISON = new Object() {
    @Override public String toString() { throw new AssertionError("secret toString"); }
  };

  @BeforeEach void reset() { TestLoggerProvider.reset(); }
  @AfterEach void clear() { TestLoggerProvider.reset(); }

  @Test void disabledSwitchOrLevelKeepsDirectBindingAndNeverInvokesPolicy() {
    AtomicInteger policies = new AtomicInteger();
    ParameterRedactor redactor = (index, type, value) -> { policies.incrementAndGet(); return "safe"; };
    for (boolean config : List.of(false, true)) {
      for (boolean level : List.of(false, true)) {
        TestLoggerProvider.reset();
        if (level) TestLoggerProvider.DEBUG.add(LOGGER);
        Driver driver = new Driver();
        CountingBinder binder = new CountingBinder();
        executor(driver, SqlLoggingOptions.builder().parameters(config).parameterRedactor(redactor).build())
            .fetchList(plan(binder), POISON);
        assertEquals(config && level ? 1 : 0, binder.observed.get());
        assertEquals(config && level ? 0 : 1, binder.normal.get());
        assertEquals(1, driver.statementCloses.get());
        assertEquals(1, driver.releases.get());
      }
    }
    assertEquals(1, policies.get());
    TestLoggerProvider.failProbe = true;
    CountingBinder binder = new CountingBinder();
    executor(new Driver(), enabled(redactor)).fetchList(plan(binder), POISON);
    assertEquals(1, binder.normal.get());
  }

  @Test void defaultTypeOnlyNeverFormatsValuesAndConfigurationSurvivesSessionCopies() {
    TestLoggerProvider.DEBUG.add(LOGGER);
    Driver driver = new Driver();
    CountingBinder binder = new CountingBinder();
    JdbcExecutor original = executor(driver, SqlLoggingOptions.builder().parameters(true).build());
    original.withConnectionProvider(driver.provider())
        .withDefaultExecutionOptions(ExecutionOptions.builder().fetchSize(2).build())
        .fetchList(plan(binder), POISON);
    assertEquals(1, binder.observed.get());
    assertTrue(message().contains("1=java.lang.Object"));
    assertTrue(message().contains("sqlFingerprint=" + FINGERPRINT + ","));
    assertFalse(message().contains("operation="));
    assertFalse(message().contains("statement="));
    assertFalse(message().contains("secret"));
  }

  @Test void parameterLevelProbeFailuresPreserveCleanupAndReleaseTheGuard() {
    for (int failure : List.of(0, 1, 2)) {
      TestLoggerProvider.reset();
      TestLoggerProvider.DEBUG.add(LOGGER);
      AtomicInteger probes = new AtomicInteger();
      TestLoggerProvider.onProbe = (name, level) -> {
        assertEquals(LOGGER, name);
        assertThrows(IllegalStateException.class,
            () -> ExecutionListenerDispatcher.NONE.startOperation(ExecutionOperationKind.FETCH_LIST, "test"));
        probes.incrementAndGet();
        if (failure == 1) throw new IllegalStateException("secret probe failure");
        if (failure == 2) throw new AssertionError("fatal probe failure");
      };
      Driver driver = new Driver();
      CountingBinder binder = new CountingBinder();
      JdbcExecutor jdbc = executor(driver, enabled(ParameterRedactor.TYPE_ONLY));
      if (failure == 2) {
        assertThrows(AssertionError.class, () -> jdbc.fetchList(plan(binder), POISON));
        assertEquals(0, binder.normal.get());
        assertEquals(0, binder.observed.get());
      } else {
        assertDoesNotThrow(() -> jdbc.fetchList(plan(binder), POISON));
        assertEquals(failure == 0 ? 0 : 1, binder.normal.get());
        assertEquals(failure == 0 ? 1 : 0, binder.observed.get());
      }
      assertTrue(probes.get() > 0);
      assertEquals(1, driver.statementCloses.get());
      assertEquals(1, driver.releases.get());
      assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
    }
  }

  @Test void opaqueCustomBinderRunsOnceAndReportsUnavailable() {
    TestLoggerProvider.DEBUG.add(LOGGER);
    AtomicInteger binds = new AtomicInteger();
    AtomicInteger policies = new AtomicInteger();
    ParameterBinder<Object> custom = (statement, index, value, context) -> {
      binds.incrementAndGet(); statement.setObject(index, value); return index + 1;
    };
    executor(new Driver(), enabled((index, type, value) -> {
      policies.incrementAndGet(); return "safe";
    })).fetchList(plan(custom), POISON);
    assertEquals(1, binds.get());
    assertEquals(0, policies.get());
    assertTrue(message().contains("[unavailable]"));
  }

  @Test void policyFailuresAreFixedMarkersAndDoNotChangeBindingOrResults() {
    TestLoggerProvider.DEBUG.add(LOGGER);
    List<ParameterRedactor> policies = List.of(
        (index, type, value) -> { throw new IllegalArgumentException("secret policy"); },
        (index, type, value) -> null,
        (index, type, value) -> "😀\n".repeat(300));
    for (ParameterRedactor policy : policies) {
      TestLoggerProvider.EVENTS.clear();
      Driver driver = new Driver();
      assertEquals(List.of(), executor(driver, enabled(policy)).fetchList(plan(new CountingBinder()), POISON));
      assertFalse(message().contains("secret"));
      assertFalse(message().contains("\n"));
      assertTrue(message().contains("[redaction-failed]") || message().contains("[truncated]"));
      assertEquals(1, driver.statementCloses.get());
    }
  }

  @Test void budgetsStopFurtherPolicyCallsAndCountEscapedUnicodeAndLabels() {
    TestLoggerProvider.DEBUG.add(LOGGER);
    AtomicInteger calls = new AtomicInteger();
    ParameterSummary slots = new ParameterSummary((index, type, value) -> {
      calls.incrementAndGet(); return "x";
    }, 100, false);
    for (int index = 1; index <= 100 && slots.needsMore(); index++) {
      slots.bound(index, Object.class, POISON);
    }
    assertFalse(slots.needsMore());
    slots.publish(LoggerFactory.getLogger(LOGGER), FINGERPRINT, 0L, 0L);
    assertEquals(64, calls.get());
    assertTrue(message().contains("[truncated=36]"));
    TestLoggerProvider.EVENTS.clear();
    calls.set(0);
    ParameterSummary characters = new ParameterSummary((index, type, value) -> {
      calls.incrementAndGet(); return "😀\n".repeat(100);
    }, 100, false);
    for (int index = 1; index <= 100 && characters.needsMore(); index++) {
      characters.bound(index, Object.class, POISON);
    }
    assertFalse(characters.needsMore());
    characters.publish(LoggerFactory.getLogger(LOGGER), FINGERPRINT, Long.MIN_VALUE, Long.MAX_VALUE);
    assertTrue(message().codePointCount(0, message().length()) <= 4096);
    assertTrue(calls.get() < 64);
    assertFalse(message().contains("\n"));
    assertTrue(message().contains("[truncated=" + (100 - calls.get()) + "]"));
    assertFalse(message().contains("[unavailable]"));
  }

  @Test void incompleteObservationBeforeTheBudgetStillReportsUnavailable() {
    TestLoggerProvider.DEBUG.add(LOGGER);
    ParameterSummary summary = new ParameterSummary(ParameterRedactor.TYPE_ONLY, 2, false);
    summary.bound(1, Object.class, POISON);
    assertTrue(summary.needsMore());
    summary.publish(LoggerFactory.getLogger(LOGGER), FINGERPRINT, 0L, 0L);
    assertTrue(message().contains("[unavailable]"));
    summary.unavailable();
    assertFalse(summary.needsMore());
  }

  @Test void budgetExhaustionDoesNotHideMoreCallbacksThanThePlanDeclares() {
    TestLoggerProvider.DEBUG.add(LOGGER);
    ParameterSummary summary = new ParameterSummary(ParameterRedactor.TYPE_ONLY, 63, false);
    for (int index = 1; index <= 64; index++) summary.bound(index, Object.class, POISON);
    assertFalse(summary.needsMore());
    summary.publish(LoggerFactory.getLogger(LOGGER), FINGERPRINT, 0L, 0L);
    assertTrue(message().contains("[unavailable]"));
    assertFalse(message().contains("[truncated=-"));
  }

  @Test void nullableValuesArePassedSynchronouslyAndTypeOnlyNeverConsumesOneShotInputs() {
    TestLoggerProvider.DEBUG.add(LOGGER);
    AtomicInteger calls = new AtomicInteger();
    ParameterSummary nullable = new ParameterSummary((index, type, value) -> {
      assertEquals(1, index);
      assertSame(String.class, type);
      assertNull(value);
      calls.incrementAndGet();
      return "approved-null";
    }, 1, false);
    nullable.bound(1, String.class, null);
    nullable.publish(LoggerFactory.getLogger(LOGGER), FINGERPRINT, 0L, 0L);
    assertEquals(1, calls.get());
    assertTrue(message().contains("1=approved-null"));
    java.io.InputStream stream = new java.io.InputStream() {
      @Override public int read() { throw new AssertionError("parameter log consumed a stream"); }
      @Override public String toString() { throw new AssertionError("parameter log formatted a stream"); }
    };
    ParameterSummary typeOnly = new ParameterSummary(ParameterRedactor.TYPE_ONLY, 1, false);
    typeOnly.bound(1, java.io.InputStream.class, stream);
    typeOnly.publish(LoggerFactory.getLogger(LOGGER), FINGERPRINT, 0L, 0L);
    assertTrue(TestLoggerProvider.EVENTS.getLast().message().contains("1=java.io.InputStream"));
  }

  @Test void incompleteBindingsEmitNothingAndKeepOriginalCauseAndSuppressedFailures() {
    TestLoggerProvider.DEBUG.add(LOGGER);
    SQLException primary = new SQLException("secret bind");
    SQLException close = new SQLException("secret close");
    Driver driver = new Driver();
    driver.closeFailure = close;
    CountingBinder binder = new CountingBinder() {
      @Override public int bindObserved(PreparedStatement statement, int index, Object value,
          JdbcWriteContext context, ParameterBindingObserver observer) throws SQLException {
        super.bindObserved(statement, index, value, context, observer);
        throw primary;
      }
    };
    QueryExecutionException failure = assertThrows(QueryExecutionException.class,
        () -> executor(driver, enabled(ParameterRedactor.TYPE_ONLY)).fetchList(plan(binder), POISON));
    assertSame(primary, failure.getCause());
    assertEquals(List.of(close), List.of(primary.getSuppressed()));
    assertTrue(TestLoggerProvider.EVENTS.isEmpty());
    assertEquals(1, driver.statementCloses.get());
    assertEquals(1, driver.releases.get());
  }

  @Test void ordinaryBackendFailureIsIgnoredAndErrorsCloseResourcesBeforePropagation() {
    TestLoggerProvider.DEBUG.add(LOGGER);
    Driver ordinary = new Driver();
    TestLoggerProvider.failure = 1;
    assertDoesNotThrow(() -> executor(ordinary, enabled(ParameterRedactor.TYPE_ONLY))
        .fetchList(plan(new CountingBinder()), POISON));
    assertEquals(1, ordinary.resultSetCloses.get());
    TestLoggerProvider.failure = 2;
    Driver fatalBackend = new Driver();
    assertThrows(AssertionError.class, () -> executor(fatalBackend, enabled(ParameterRedactor.TYPE_ONLY))
        .fetchList(plan(new CountingBinder()), POISON));
    assertEquals(1, fatalBackend.statementCloses.get());
    assertEquals(1, fatalBackend.releases.get());
    TestLoggerProvider.failure = 0;
    Driver fatalPolicy = new Driver();
    fatalPolicy.closeFailure = new SQLException("secret cleanup after fatal policy");
    AssertionError policyError = new AssertionError("fatal policy");
    AssertionError thrown = assertThrows(AssertionError.class, () -> executor(fatalPolicy, enabled((index, type, value) -> {
      throw policyError;
    })).fetchList(plan(new CountingBinder()), POISON));
    assertSame(policyError, thrown);
    assertEquals(List.of(fatalPolicy.closeFailure), List.of(thrown.getSuppressed()));
    assertEquals(1, fatalPolicy.statementCloses.get());
    assertEquals(1, fatalPolicy.releases.get());
  }

  @Test void anOrdinaryLoggingFailureCannotMaskStatementCleanupFailure() {
    TestLoggerProvider.DEBUG.add(LOGGER);
    TestLoggerProvider.failure = 1;
    Driver driver = new Driver();
    driver.closeFailure = new SQLException("secret statement cleanup");
    QueryExecutionException failure = assertThrows(QueryExecutionException.class,
        () -> executor(driver, enabled(ParameterRedactor.TYPE_ONLY)).fetchList(plan(new CountingBinder()), POISON));
    assertSame(driver.closeFailure, failure.getCause());
    assertEquals(1, driver.releases.get());
    assertTrue(TestLoggerProvider.EVENTS.isEmpty());
  }

  @Test void fatalCompletionLoggingPreservesJdbcAndCleanupFailuresForMaterializedOperations() {
    for (var kind : List.of(ExecutionOperationKind.FETCH_LIST, ExecutionOperationKind.FETCH_ONE,
        ExecutionOperationKind.UPDATE_BY_ID)) {
      TestLoggerProvider.reset();
      TestLoggerProvider.DEBUG.add("io.skis.sql");
      var options = SqlLoggingOptions.builder().sql(true).build();
      var operation = ExecutionListenerDispatcher.of(List.of(failCompletionLogging()), options)
          .startOperation(kind, "test");
      Objects.requireNonNull(operation);
      Driver driver = new Driver();
      driver.executeFailure = new SQLException("secret execute");
      driver.closeFailure = new SQLException("secret statement close");
      driver.releaseFailure = new SQLException("secret release");
      JdbcExecutor jdbc = executor(driver, options);
      var query = plan(new CountingBinder());
      AssertionError fatal = assertThrows(AssertionError.class, () -> {
        switch (kind) {
          case FETCH_LIST -> jdbc.fetchList(query, POISON, ExecutionContext.EMPTY, operation,
              StatementPurpose.PRIMARY);
          case FETCH_ONE -> jdbc.fetchOne(query, POISON, ExecutionContext.EMPTY, operation,
              StatementPurpose.PRIMARY);
          case UPDATE_BY_ID -> jdbc.executeUpdate(mutationPlan(), POISON, ExecutionContext.EMPTY,
              operation, StatementPurpose.PRIMARY);
          default -> throw new AssertionError("unexpected operation");
        }
      });
      assertEquals(2, fatal.getSuppressed().length);
      Throwable primary = fatal.getSuppressed()[0];
      if (kind == ExecutionOperationKind.UPDATE_BY_ID) {
        assertInstanceOf(JdbcExecutionException.class, primary);
      } else {
        assertInstanceOf(QueryExecutionException.class, primary);
      }
      assertSame(driver.executeFailure, primary.getCause());
      assertEquals(List.of(driver.closeFailure), List.of(driver.executeFailure.getSuppressed()));
      assertSame(driver.releaseFailure, fatal.getSuppressed()[1]);
      assertEquals(1, driver.statementCloses.get());
      assertEquals(1, driver.releases.get());
      assertFalse(TestLoggerProvider.EVENTS.toString().contains("secret"));
      operation.completeFailed(fatal, SkisDiagnosticStage.EXECUTION);
    }
  }

  @Test void fatalCursorOpenCompletionLoggingPreservesThePrimaryExactlyOnce() {
    TestLoggerProvider.DEBUG.add("io.skis.sql");
    var options = SqlLoggingOptions.builder().sql(true).build();
    var operation = ExecutionListenerDispatcher.of(List.of(failCompletionLogging()), options)
        .startOperation(ExecutionOperationKind.CURSOR, "test");
    Objects.requireNonNull(operation);
    Driver driver = new Driver();
    driver.executeFailure = new SQLException("secret cursor open");
    driver.closeFailure = new SQLException("secret cursor statement close");
    driver.releaseFailure = new SQLException("secret cursor release");
    AssertionError fatal = assertThrows(AssertionError.class,
        () -> executor(driver, options).openCursor(plan(new CountingBinder()), POISON,
            ExecutionContext.EMPTY, operation, StatementPurpose.PRIMARY));
    assertEquals(2, fatal.getSuppressed().length);
    Throwable primary = fatal.getSuppressed()[0];
    assertInstanceOf(QueryExecutionException.class, primary);
    assertSame(driver.executeFailure, primary.getCause());
    assertEquals(1, primary.getSuppressed().length);
    assertSame(driver.closeFailure, primary.getSuppressed()[0].getCause());
    assertSame(driver.releaseFailure, fatal.getSuppressed()[1].getCause());
    assertEquals(1, driver.statementCloses.get());
    assertEquals(1, driver.releases.get());
    operation.completeFailed(fatal, SkisDiagnosticStage.EXECUTION);
  }

  @Test void summariesAndLifecycleEventsShareTheIdsAndFingerprintForEachSqlAttempt() {
    TestLoggerProvider.DEBUG.add(LOGGER);
    TestLoggerProvider.DEBUG.add("io.skis.sql");
    var starts = new java.util.ArrayList<StatementStartedEvent>();
    var options = SqlLoggingOptions.builder().sql(true).parameters(true).build();
    var dispatcher = ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {
      @Override public void statementStarted(StatementStartedEvent event) { starts.add(event); }
    }), options);
    Driver driver = new Driver();
    JdbcExecutor jdbc = executor(driver, options);
    for (var kind : List.of(ExecutionOperationKind.FETCH_LIST, ExecutionOperationKind.FETCH_ONE,
        ExecutionOperationKind.CURSOR, ExecutionOperationKind.UPDATE_BY_ID)) {
      var operation = Objects.requireNonNull(dispatcher.startOperation(kind, "test"));
      switch (kind) {
        case FETCH_LIST -> jdbc.fetchList(plan(new CountingBinder()), POISON,
            ExecutionContext.EMPTY, operation, StatementPurpose.PRIMARY);
        case FETCH_ONE -> jdbc.fetchOne(plan(new CountingBinder()), POISON,
            ExecutionContext.EMPTY, operation, StatementPurpose.PRIMARY);
        case CURSOR -> {
          try (var cursor = jdbc.openCursor(plan(new CountingBinder()), POISON,
              ExecutionContext.EMPTY, operation, StatementPurpose.PRIMARY)) {
            assertFalse(cursor.advance());
          }
        }
        case UPDATE_BY_ID -> jdbc.executeUpdate(mutationPlan(), POISON,
            ExecutionContext.EMPTY, operation, StatementPurpose.PRIMARY);
        default -> throw new AssertionError("unexpected operation");
      }
      if (kind != ExecutionOperationKind.CURSOR) {
        operation.completeSucceeded(ExecutionTermination.COMPLETED);
      }
    }
    var summaries = TestLoggerProvider.EVENTS.stream()
        .filter(entry -> entry.logger().equals(LOGGER)).toList();
    assertEquals(4, summaries.size());
    assertEquals(4, starts.size());
    for (int index = 0; index < starts.size(); index++) {
      assertTrue(summaries.get(index).message().contains(
          "sqlFingerprint=" + starts.get(index).sqlFingerprint()
              + ", operation=" + starts.get(index).operationId()
              + ", statement=" + starts.get(index).statementId() + ","));
    }
    assertNotEquals(starts.getFirst().sqlFingerprint(), starts.getLast().sqlFingerprint());
    assertEquals(4, driver.statementCloses.get());
    assertEquals(4, driver.releases.get());
  }

  @Test void abandonedCursorWarningsDoNotCloseResourcesOrExposeValuesWhenTheBackendFails()
      throws Exception {
    TestLoggerProvider.WARN.add("io.skis.internal");
    Driver driver = new Driver();
    try (JdbcCursor<String> cursor = executor(driver, SqlLoggingOptions.NONE)
        .openCursor(plan(new CountingBinder()), POISON, ExecutionContext.EMPTY)) {
      var resourcesField = cursor.getClass().getDeclaredField("resources");
      resourcesField.setAccessible(true);
      Runnable warning = (Runnable) resourcesField.get(cursor);
      warning.run();
      assertFalse(message().contains("SELECT ?"));
      assertFalse(message().contains("secret"));
      TestLoggerProvider.failure = 1;
      assertDoesNotThrow(warning::run);
      TestLoggerProvider.failure = 2;
      assertThrows(AssertionError.class, warning::run);
      assertEquals(0, driver.resultSetCloses.get());
      assertEquals(0, driver.statementCloses.get());
      assertEquals(0, driver.releases.get());
      TestLoggerProvider.failure = 0;
    }
    assertEquals(1, driver.resultSetCloses.get());
    assertEquals(1, driver.statementCloses.get());
    assertEquals(1, driver.releases.get());
  }

  @Test void oneSharedPlanUsesIndependentSummariesAcrossConcurrentCalls() throws Exception {
    TestLoggerProvider.DEBUG.add(LOGGER);
    Driver driver = new Driver();
    CountingBinder binder = new CountingBinder();
    var plan = plan(binder);
    JdbcExecutor executor = executor(driver, enabled((index, type, value) -> {
      if (value instanceof ApprovedParameter parameter) return parameter.summary();
      throw new AssertionError("unexpected parameter type");
    }));
    var expected = new java.util.HashSet<String>();
    var ready = new java.util.concurrent.CountDownLatch(20);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int index = 0; index < 20; index++) {
        var parameter = new ApprovedParameter("approved-" + index);
        expected.add("SQL parameters [sqlFingerprint=" + FINGERPRINT
            + ", parameters=[1=" + parameter.summary() + "]]");
        futures.add(workers.submit(() -> {
          ready.countDown();
          assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
          return executor.fetchList(plan, parameter);
        }));
      }
      try {
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        start.countDown();
      }
      for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertEquals(20, binder.observed.get());
    assertEquals(20, TestLoggerProvider.EVENTS.size());
    var actual = TestLoggerProvider.EVENTS.stream().map(TestLoggerProvider.Entry::message)
        .collect(java.util.stream.Collectors.toSet());
    assertEquals(20, actual.size());
    assertEquals(expected, actual);
    assertEquals(20, driver.releases.get());
  }

  @Test void concurrentAttemptsOfTheSameSqlHaveDistinctLifecycleCorrelations() throws Exception {
    TestLoggerProvider.DEBUG.add(LOGGER);
    var starts = new java.util.concurrent.ConcurrentHashMap<Long, StatementStartedEvent>();
    var completions = new java.util.concurrent.ConcurrentHashMap<Long, StatementCompletedEvent>();
    var dispatcher = ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {
      @Override public void statementStarted(StatementStartedEvent event) {
        starts.put(event.operationId(), event);
      }
      @Override public void statementCompleted(StatementCompletedEvent event) {
        completions.put(event.operationId(), event);
      }
    }));
    Driver driver = new Driver();
    var sharedPlan = plan(new CountingBinder());
    JdbcExecutor jdbc = executor(driver, enabled(ParameterRedactor.TYPE_ONLY));
    var expected = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    var ready = new java.util.concurrent.CountDownLatch(8);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int index = 0; index < 8; index++) {
        futures.add(workers.submit(() -> {
          ready.countDown();
          assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
          var operation = Objects.requireNonNull(
              dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, "test"));
          jdbc.fetchList(sharedPlan, POISON, ExecutionContext.EMPTY, operation, StatementPurpose.PRIMARY);
          operation.completeSucceeded(ExecutionTermination.COMPLETED);
          var event = Objects.requireNonNull(starts.get(operation.operationId()));
          assertEquals(event.statementId(), Objects.requireNonNull(
              completions.get(operation.operationId())).statementId());
          expected.add("SQL parameters [sqlFingerprint=" + event.sqlFingerprint()
              + ", operation=" + event.operationId() + ", statement=" + event.statementId()
              + ", parameters=[1=java.lang.Object]]");
          return null;
        }));
      }
      try {
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        start.countDown();
      }
      for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    var actual = TestLoggerProvider.EVENTS.stream().map(TestLoggerProvider.Entry::message)
        .collect(java.util.stream.Collectors.toSet());
    assertEquals(8, TestLoggerProvider.EVENTS.size());
    assertEquals(8, expected.size());
    assertEquals(expected, actual);
    assertEquals(8, driver.releases.get());
  }

  @Test
  @org.junit.jupiter.api.Timeout(10)
  void summaryTimingRequiresObservationAndIsAccountedForEvenWhenLoggingFails()
      throws Exception {
    var callbackNanos = OperationObservation.class.getDeclaredField("callbackNanos");
    callbackNanos.setAccessible(true);
    for (boolean observed : List.of(false, true)) {
      for (int failure : List.of(0, 1, 2, 3, 4, 5)) {
        TestLoggerProvider.reset();
        TestLoggerProvider.DEBUG.add(LOGGER);
        TestLoggerProvider.failure = failure == 3 ? 1 : failure == 4 ? 2 : 0;
        var policyNanos = new AtomicLong();
        var outputNanos = new AtomicLong();
        TestLoggerProvider.onLog = () -> outputNanos.set(elapseLoggingTime());
        var summaryReference = new AtomicReference<ParameterSummary>();
        var boundNanos = new AtomicLong();
        CountingBinder binder = new CountingBinder() {
          @Override public int bindObserved(PreparedStatement statement, int index, Object value,
              JdbcWriteContext context, ParameterBindingObserver observer) throws SQLException {
            ParameterSummary summary = (ParameterSummary) observer;
            summaryReference.set(summary);
            int next = super.bindObserved(statement, index, value, context, observer);
            boundNanos.set(summary.loggingNanos());
            if (failure == 5) throw new SQLException("secret binding failure");
            return next;
          }
        };
        var options = enabled((index, type, value) -> {
          policyNanos.set(elapseLoggingTime());
          if (failure == 1) throw new IllegalStateException("secret policy failure");
          if (failure == 2) throw new AssertionError("fatal policy failure");
          return "approved";
        });
        var operation = observed
            ? Objects.requireNonNull(ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {}))
                .startOperation(ExecutionOperationKind.FETCH_LIST, "test")) : null;
        long before = operation == null ? 0L : callbackNanos.getLong(operation);
        Driver driver = new Driver();
        JdbcExecutor jdbc = executor(driver, options);
        var query = plan(binder);
        Throwable primary = null;
        if (failure == 2 || failure == 4) {
          primary = assertThrows(AssertionError.class, () -> jdbc.fetchList(query, POISON,
              ExecutionContext.EMPTY, operation, StatementPurpose.PRIMARY));
        } else if (failure == 5) {
          primary = assertThrows(QueryExecutionException.class, () -> jdbc.fetchList(query, POISON,
              ExecutionContext.EMPTY, operation, StatementPurpose.PRIMARY));
        } else {
          assertDoesNotThrow(() -> jdbc.fetchList(query, POISON,
              ExecutionContext.EMPTY, operation, StatementPurpose.PRIMARY));
        }
        ParameterSummary summary = java.util.Objects.requireNonNull(summaryReference.get());
        assertTrue(policyNanos.get() > 0L);
        boolean published = failure != 2 && failure != 5;
        assertEquals(published, outputNanos.get() > 0L);
        if (operation == null) {
          assertEquals(0L, summary.loggingNanos());
        } else {
          assertTrue(summary.loggingNanos() >= policyNanos.get(), "redaction time was not measured");
          if (published) {
            assertTrue(summary.loggingNanos() - boundNanos.get() >= outputNanos.get(),
                "output time was not measured");
          }
          assertTrue(callbackNanos.getLong(operation) - before >= summary.loggingNanos());
          if (primary == null) {
            operation.completeSucceeded(ExecutionTermination.COMPLETED);
          } else {
            operation.completeFailed(primary, SkisDiagnosticStage.EXECUTION);
          }
        }
        assertEquals(1, driver.statementCloses.get());
        assertEquals(1, driver.releases.get());
        assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
      }
    }
  }

  // Wait for a measured interval inside each timed callback, including callbacks that throw.
  private static long elapseLoggingTime() {
    long started = System.nanoTime();
    long elapsed;
    do {
      if (Thread.currentThread().isInterrupted()) throw new AssertionError("timing fixture interrupted");
      Thread.onSpinWait();
      elapsed = System.nanoTime() - started;
    } while (elapsed < 1_000_000L);
    return elapsed;
  }

  private record ApprovedParameter(String summary) {
    @Override public String toString() { throw new AssertionError("parameter formatted directly"); }
  }

  @Test void aLiveSummaryDoesNotRetainTheValueAfterTheSynchronousCallback() throws Exception {
    ParameterSummary summary = new ParameterSummary(ParameterRedactor.TYPE_ONLY, 1, false);
    WeakReference<Object> reference = observeTemporaryValue(summary);
    for (int attempt = 0; attempt < 80 && !reference.refersTo(null); attempt++) {
      System.gc();
      Thread.sleep(25);
    }
    assertTrue(reference.refersTo(null), "summary retained the original parameter value");
    java.lang.ref.Reference.reachabilityFence(summary);
  }

  private static WeakReference<Object> observeTemporaryValue(ParameterSummary summary) {
    Object value = new Object();
    summary.bound(1, Object.class, value);
    return new WeakReference<>(value);
  }

  private static String message() { return TestLoggerProvider.EVENTS.getFirst().message(); }
  private static SqlLoggingOptions enabled(ParameterRedactor policy) {
    return SqlLoggingOptions.builder().parameters(true).parameterRedactor(policy).build();
  }
  private static JdbcExecutor executor(Driver driver, SqlLoggingOptions options) {
    return new JdbcExecutor(driver.provider(), JdbcWriteContext.EMPTY, RowReadContext.EMPTY,
        ExecutionOptions.NONE, ExceptionClassifier.NONE, options);
  }
  private static CompiledQueryPlan<String, Object> plan(ParameterBinder<Object> binder) {
    return new CompiledQueryPlan<>("test", new RenderedSql("SELECT ?",
        List.of(new ParameterSlot<>(0, Object.class, false))), binder, (rs, context) -> "unused");
  }

  private static CompiledMutationPlan<Object> mutationPlan() {
    return new CompiledMutationPlan<>("updateById", "test",
        new RenderedSql("UPDATE pet SET name = ?",
            List.of(new ParameterSlot<>(0, Object.class, false))), new CountingBinder());
  }

  private static ExecutionListener failCompletionLogging() {
    return new ExecutionListener() {
      @Override public void statementCompleted(StatementCompletedEvent event) {
        TestLoggerProvider.failure = 2;
      }
    };
  }

  private static class CountingBinder implements ParameterBinder<Object> {
    final AtomicInteger normal = new AtomicInteger();
    final AtomicInteger observed = new AtomicInteger();
    @Override public int bind(PreparedStatement statement, int index, Object value,
        JdbcWriteContext context) throws SQLException {
      normal.incrementAndGet(); statement.setObject(index, value); return index + 1;
    }
    @Override public int bindObserved(PreparedStatement statement, int index, Object value,
        JdbcWriteContext context, ParameterBindingObserver observer) throws SQLException {
      observed.incrementAndGet();
      statement.setObject(index, value);
      observer.bound(index, Object.class, value);
      return index + 1;
    }
  }

  private static final class Driver {
    final AtomicInteger statementCloses = new AtomicInteger();
    final AtomicInteger resultSetCloses = new AtomicInteger();
    final AtomicInteger releases = new AtomicInteger();
    SQLException executeFailure;
    SQLException closeFailure;
    SQLException releaseFailure;
    ConnectionProvider provider() {
      return new ConnectionProvider() {
        @Override public Connection acquire(ExecutionContext context) {
          return proxy(Connection.class, (object, method, args) -> {
            if (!method.getName().equals("prepareStatement")) {
              throw new AssertionError("unexpected Connection call: " + method.getName());
            }
            return proxy(PreparedStatement.class, (statement, call, values) -> switch (call.getName()) {
              case "executeQuery" -> {
                if (executeFailure != null) throw executeFailure;
                yield proxy(ResultSet.class, (rs, read, ignored) -> switch (read.getName()) {
                  case "next" -> false;
                  case "close" -> { resultSetCloses.incrementAndGet(); yield null; }
                  default -> throw new AssertionError("unexpected ResultSet call: " + read.getName());
                });
              }
              case "executeUpdate" -> {
                if (executeFailure != null) throw executeFailure;
                yield 1;
              }
              case "close" -> {
                statementCloses.incrementAndGet();
                if (closeFailure != null) throw closeFailure;
                yield null;
              }
              case "setObject", "setFetchSize", "setMaxRows", "setQueryTimeout" -> null;
              default -> throw new AssertionError("unexpected PreparedStatement call: " + call.getName());
            });
          });
        }
        @Override public void release(Connection connection, ExecutionContext context) throws SQLException {
          releases.incrementAndGet();
          if (releaseFailure != null) throw releaseFailure;
        }
      };
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }
}
