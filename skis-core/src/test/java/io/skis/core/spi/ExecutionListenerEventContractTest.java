package io.skis.core.spi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisOperation;
import io.skis.core.SkisStatementKind;
import org.junit.jupiter.api.Test;

class ExecutionListenerEventContractTest {

  @Test
  void exposesStableEnumLabelsAndOperationFamilies() {
    assertEquals("on-demand", CompilationOrigin.ON_DEMAND.label());
    assertEquals("prewarm", CompilationOrigin.PREWARM.label());
    assertEquals("succeeded", ExecutionOutcome.SUCCEEDED.label());
    assertEquals("failed", ExecutionOutcome.FAILED.label());
    assertEquals("page-content", StatementPurpose.PAGE_CONTENT.label());
    assertEquals("closed-early", ExecutionTermination.CLOSED_EARLY.label());
    assertEquals(SkisOperation.QUERY, ExecutionOperationKind.STREAM.operation());
    assertEquals(SkisOperation.MUTATION, ExecutionOperationKind.UPDATE_BY_ID.operation());
  }

  @Test
  void acceptsTheFiveIndependentSuccessfulEventShapes() {
    assertDoesNotThrow(
        () ->
            new CompilationCompletedEvent(
                1L,
                CompilationOrigin.ON_DEMAND,
                ExecutionOperationKind.FETCH_LIST,
                StatementPurpose.PRIMARY,
                SkisStatementKind.SELECT,
                "test",
                ExecutionOutcome.SUCCEEDED,
                2L,
                "abc",
                "select value from sample where id = ?",
                null,
                null));
    assertDoesNotThrow(
        () -> new OperationStartedEvent(1L, ExecutionOperationKind.FETCH_LIST, "test"));
    assertDoesNotThrow(
        () ->
            new OperationCompletedEvent(
                1L,
                ExecutionOperationKind.FETCH_LIST,
                "test",
                ExecutionOutcome.SUCCEEDED,
                ExecutionTermination.COMPLETED,
                3L,
                null,
                null));
    assertDoesNotThrow(
        () ->
            new StatementStartedEvent(
                1L,
                2L,
                ExecutionOperationKind.FETCH_LIST,
                StatementPurpose.PRIMARY,
                SkisStatementKind.SELECT,
                "test",
                "abc",
                "select value from sample where id = ?"));
    assertDoesNotThrow(
        () ->
            new StatementCompletedEvent(
                1L,
                2L,
                ExecutionOperationKind.FETCH_LIST,
                StatementPurpose.PRIMARY,
                SkisStatementKind.SELECT,
                "test",
                "abc",
                "select value from sample where id = ?",
                ExecutionOutcome.SUCCEEDED,
                ExecutionTermination.COMPLETED,
                4L,
                1L,
                0L,
                StatementCompletedEvent.UNKNOWN_ROW_COUNT,
                null,
                null));
  }

  @Test
  void rejectsInvalidIdsSentinelsAndCompletionCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompilationCompletedEvent(
                1L,
                CompilationOrigin.PREWARM,
                ExecutionOperationKind.FIND_BY_ID,
                StatementPurpose.PRIMARY,
                SkisStatementKind.SELECT,
                "test",
                ExecutionOutcome.SUCCEEDED,
                0L,
                "abc",
                "select value from sample",
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CompilationCompletedEvent(
                1L,
                CompilationOrigin.ON_DEMAND,
                ExecutionOperationKind.FETCH_LIST,
                StatementPurpose.PRIMARY,
                SkisStatementKind.SELECT,
                "test",
                ExecutionOutcome.SUCCEEDED,
                0L,
                null,
                null,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationCompletedEvent(
                1L,
                ExecutionOperationKind.FETCH_LIST,
                "test",
                ExecutionOutcome.FAILED,
                ExecutionTermination.COMPLETED,
                0L,
                SkisDiagnosticStage.EXECUTION,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationCompletedEvent(
                1L,
                ExecutionOperationKind.FETCH_LIST,
                "test",
                ExecutionOutcome.SUCCEEDED,
                ExecutionTermination.EXHAUSTED,
                0L,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationCompletedEvent(
                1L,
                ExecutionOperationKind.CURSOR,
                "test",
                ExecutionOutcome.SUCCEEDED,
                ExecutionTermination.COMPLETED,
                0L,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StatementCompletedEvent(
                1L,
                2L,
                ExecutionOperationKind.FETCH_LIST,
                StatementPurpose.PRIMARY,
                SkisStatementKind.SELECT,
                "test",
                "abc",
                "select value from sample",
                ExecutionOutcome.FAILED,
                ExecutionTermination.FAILED,
                0L,
                -2L,
                StatementCompletedEvent.UNKNOWN_ROW_COUNT,
                StatementCompletedEvent.UNKNOWN_ROW_COUNT,
                SkisDiagnosticStage.EXECUTION,
                null));
  }
}
