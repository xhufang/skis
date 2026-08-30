package io.skis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExecutionOptionsTest {

  @Test
  void reusesEmptyOptionsAndEmptyExecutionContext() {
    ExecutionOptions options = ExecutionOptions.builder().build();

    assertSame(ExecutionOptions.NONE, options);
    assertTrue(options.isEmpty());
    assertSame(ExecutionContext.EMPTY, ExecutionContext.of(options));
    assertSame(ExecutionOptions.NONE, ExecutionContext.EMPTY.executionOptions());
  }

  @Test
  void validatesAndPrecomputesJdbcValues() {
    ExecutionOptions options =
        ExecutionOptions.builder()
            .statementTimeout(Duration.ofMillis(1_001))
            .fetchSize(256)
            .maxRows(1_000)
            .queryTag("pet.lookup-v1")
            .build();

    assertFalse(options.isEmpty());
    assertEquals(Duration.ofMillis(1_001), options.statementTimeout());
    assertEquals(2, options.queryTimeoutSeconds());
    assertEquals(256, options.fetchSize());
    assertEquals(1_000, options.maxRows());
    assertEquals(QueryTag.of("pet.lookup-v1"), options.queryTag());
    assertSame(options, ExecutionContext.of(options).executionOptions());
    assertSame(ExecutionContext.of(options), ExecutionContext.of(options));
  }

  @Test
  void keepsZeroDistinctFromUnsetAndCanClearADefaultTag() {
    ExecutionOptions options =
        ExecutionOptions.builder()
            .statementTimeout(Duration.ZERO)
            .fetchSize(0)
            .maxRows(0)
            .clearQueryTag()
            .build();

    assertTrue(options.hasStatementTimeout());
    assertEquals(0, options.queryTimeoutSeconds());
    assertTrue(options.hasFetchSize());
    assertEquals(0, options.fetchSize());
    assertTrue(options.hasMaxRows());
    assertEquals(0, options.maxRows());
    assertTrue(options.isQueryTagConfigured());
    assertNull(options.queryTag());
  }

  @Test
  void overlaysSessionDefaultsOnceWhileKeepingUnsetExecutorFields() {
    ExecutionOptions executorDefaults =
        ExecutionOptions.builder()
            .statementTimeout(Duration.ofSeconds(30))
            .fetchSize(128)
            .maxRows(500)
            .queryTag("executor")
            .build();
    ExecutionOptions sessionDefaults =
        ExecutionOptions.builder().fetchSize(32).maxRows(0).clearQueryTag().build();

    ExecutionOptions effective = executorDefaults.overriddenBy(sessionDefaults);

    assertEquals(30, effective.queryTimeoutSeconds());
    assertEquals(32, effective.fetchSize());
    assertEquals(0, effective.maxRows());
    assertTrue(effective.isQueryTagConfigured());
    assertNull(effective.queryTag());
    assertSame(executorDefaults, executorDefaults.overriddenBy(ExecutionOptions.NONE));
    assertSame(sessionDefaults, ExecutionOptions.NONE.overriddenBy(sessionDefaults));
  }

  @Test
  void rejectsNegativeAndUnrepresentableNumericOptions() {
    ExecutionOptions maximum =
        ExecutionOptions.builder()
            .statementTimeout(Duration.ofSeconds(Integer.MAX_VALUE))
            .fetchSize(Integer.MAX_VALUE)
            .maxRows(Integer.MAX_VALUE)
            .build();

    assertEquals(Integer.MAX_VALUE, maximum.queryTimeoutSeconds());
    assertEquals(Integer.MAX_VALUE, maximum.fetchSize());
    assertEquals(Integer.MAX_VALUE, maximum.maxRows());
    assertThrows(
        IllegalArgumentException.class,
        () -> ExecutionOptions.builder().statementTimeout(Duration.ofNanos(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ExecutionOptions.builder()
                .statementTimeout(Duration.ofSeconds((long) Integer.MAX_VALUE + 1L)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ExecutionOptions.builder()
                .statementTimeout(Duration.ofSeconds(Integer.MAX_VALUE, 1)));
    assertThrows(
        IllegalArgumentException.class, () -> ExecutionOptions.builder().fetchSize(-1));
    assertThrows(
        IllegalArgumentException.class, () -> ExecutionOptions.builder().maxRows(-1));
  }

  @Test
  void queryTagsUseASmallInjectionSafeAlphabet() {
    assertEquals("orders/read:batch_1", QueryTag.of("orders/read:batch_1").value());
    assertEquals(QueryTag.MAX_LENGTH, QueryTag.of("x".repeat(QueryTag.MAX_LENGTH)).value().length());
    assertThrows(IllegalArgumentException.class, () -> QueryTag.of(""));
    assertThrows(IllegalArgumentException.class, () -> QueryTag.of(" leading"));
    assertThrows(IllegalArgumentException.class, () -> QueryTag.of("trailing "));
    assertThrows(IllegalArgumentException.class, () -> QueryTag.of("close */ SELECT"));
    assertThrows(IllegalArgumentException.class, () -> QueryTag.of("first; DELETE"));
    assertThrows(IllegalArgumentException.class, () -> QueryTag.of("line\nbreak"));
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryTag.of("x".repeat(QueryTag.MAX_LENGTH + 1)));
  }
}
