package io.skis.mutation.internal;

import static org.junit.jupiter.api.Assertions.*;
import io.skis.core.MutationException;
import io.skis.core.SkisErrorCode;
import io.skis.jdbc.internal.JdbcBatchOutcome;
import io.skis.mutation.BatchResult;
import java.sql.Statement;
import java.util.*;
import org.junit.jupiter.api.Test;

class BatchResultTest {
  @Test void uniformCompletedChunksRetainNoCountArraysAndExpandOnlyForFailureResults()
      throws ReflectiveOperationException {
    var ledger = new BatchWrites.Ledger(6);
    int[] ones = {1, 1};
    int[] zeros = {0, 0};
    ledger.begin(0, 2);
    ledger.record(new JdbcBatchOutcome(ones, 2, true, -1, null), false);
    ledger.begin(2, 2);
    ledger.record(new JdbcBatchOutcome(zeros, 2, true, -1, null), true);
    ones[0] = 99;
    zeros[0] = 99;
    var reports = BatchWrites.Ledger.class.getDeclaredField("reports");
    reports.setAccessible(true);
    for (Object chunk : (List<?>) reports.get(ledger))
      assertFalse(Arrays.stream(chunk.getClass().getDeclaredFields())
          .anyMatch(field -> field.getType().isArray()), "uniform chunks must not retain arrays");
    ledger.begin(4, 1);
    var result = ledger.failure(MutationException.of(
        SkisErrorCode.MUTATION_PARAMETER_SHAPE, "preparation failed")).result();
    assertEquals(1, result.items().get(0).affectedRows());
    assertEquals(1, result.items().get(1).affectedRows());
    assertEquals(BatchResult.Status.FAILED, result.items().get(2).status());
    assertEquals(BatchResult.Status.FAILED, result.items().get(3).status());
    assertTrue(result.items().get(3).countKnown());
    assertEquals(0, result.items().get(3).affectedRows());
    assertEquals(BatchResult.Status.NOT_EXECUTED, result.items().get(4).status());
    assertEquals(BatchResult.Status.NOT_EXECUTED, result.items().get(5).status());
    assertEquals(3, result.chunks().size());
    assertEquals(2, result.chunks().get(1).firstInput());
    assertEquals(2, result.knownAffectedRows());
    assertTrue(result.affectedRowsKnown());
  }

  @Test void mixedAndSpecialDriverCountsRemainExactSnapshotsAfterLaterFailure() {
    var ledger = new BatchWrites.Ledger(5);
    int[] mixed = {1, 0};
    ledger.begin(0, 2);
    ledger.record(new JdbcBatchOutcome(mixed, 2, true, -1, null), false);
    mixed[0] = 99;
    ledger.begin(2, 2);
    ledger.record(new JdbcBatchOutcome(new int[] {Statement.SUCCESS_NO_INFO},
        2, true, -1, null), false);
    ledger.begin(4, 1);
    var result = ledger.failure(MutationException.of(
        SkisErrorCode.MUTATION_PARAMETER_SHAPE, "preparation failed")).result();
    assertEquals(1, result.items().get(0).affectedRows());
    assertEquals(0, result.items().get(1).affectedRows());
    assertTrue(result.items().get(1).countKnown());
    assertEquals(BatchResult.Status.SUCCEEDED, result.items().get(2).status());
    assertFalse(result.items().get(2).countKnown());
    assertEquals(BatchResult.Status.UNKNOWN, result.items().get(3).status());
    assertEquals(BatchResult.Status.NOT_EXECUTED, result.items().get(4).status());
    assertEquals(1, result.knownAffectedRows());
    assertFalse(result.affectedRowsKnown());
  }

  @Test void aggregateChunksKeepUnknownPerInputCountsAfterFailure() {
    var ledger = new BatchWrites.Ledger(4);
    ledger.begin(0, 3);
    ledger.aggregate(2, false);
    ledger.begin(3, 1);
    var result = ledger.failure(MutationException.of(
        SkisErrorCode.MUTATION_PARAMETER_SHAPE, "preparation failed")).result();
    assertEquals(2, result.knownAffectedRows());
    assertTrue(result.affectedRowsKnown());
    for (int index = 0; index < 3; index++) {
      assertEquals(BatchResult.Status.SUCCEEDED, result.items().get(index).status());
      assertFalse(result.items().get(index).countKnown());
    }
    assertEquals(BatchResult.Status.NOT_EXECUTED, result.items().get(3).status());
  }

  @Test void specialCountsAndMissingDriverReportsRemainDistinctFromUnexecutedInputs() {
    int[] counts = {1, Statement.SUCCESS_NO_INFO, Statement.EXECUTE_FAILED};
    var report = new BatchResult.Report(0, 4, 4, true, -1, counts, false, false, 0);
    counts[0] = 99;
    var result = BatchResult.fromReports(6, List.of(report));
    assertEquals(BatchResult.Status.SUCCEEDED, result.items().get(0).status());
    assertEquals(1, result.items().get(0).affectedRows());
    assertEquals(BatchResult.Status.SUCCEEDED, result.items().get(1).status());
    assertFalse(result.items().get(1).countKnown());
    assertEquals(BatchResult.Status.FAILED, result.items().get(2).status());
    assertEquals(BatchResult.Status.UNKNOWN, result.items().get(3).status());
    assertEquals(BatchResult.Status.NOT_EXECUTED, result.items().get(4).status());
    assertEquals(-1, result.items().get(4).chunkIndex());
    assertEquals(1, result.knownAffectedRows()); assertFalse(result.affectedRowsKnown());
    assertThrows(UnsupportedOperationException.class, () -> result.items().clear());
  }
  @Test void aggregateCountsDoNotInventPerInputCorrespondence() {
    var result = BatchResult.fromReports(3, List.of(new BatchResult.Report(0, 3, 3, true, -1, new int[0], true, true, 2)));
    assertTrue(result.affectedRowsKnown()); assertEquals(2, result.knownAffectedRows());
    assertEquals(BatchResult.Status.SUCCEEDED, result.items().get(0).status());
    assertFalse(result.items().get(0).countKnown());
  }
  @Test void bindFailureReportsNoDatabaseExecutionAndDetachedNullRowsSurvive() {
    var result = BatchResult.fromReports(4, List.of(new BatchResult.Report(0, 3, 1, false, 1, new int[0], false, false, 0)));
    assertEquals(BatchResult.Status.NOT_EXECUTED, result.items().get(0).status());
    assertEquals(BatchResult.Status.FAILED, result.items().get(1).status());
    assertEquals(0, result.knownAffectedRows()); assertTrue(result.affectedRowsKnown());
    var withNull = result.withRows(Collections.singletonList(null));
    assertEquals(Collections.singletonList(null), withNull.rows());
    assertThrows(UnsupportedOperationException.class, () -> withNull.rows().clear());
  }
  @Test void equalAggregateAndInputCountsStillDoNotProveIndividualCounts() {
    var result = BatchResult.fromReports(3, List.of(new BatchResult.Report(0, 3, 3, true, -1, new int[0], true, true, 3)));
    assertEquals(3, result.knownAffectedRows());
    assertTrue(result.affectedRowsKnown());
    assertTrue(result.items().stream().noneMatch(BatchResult.Item::countKnown));
  }

  @Test void partialNullRowsAreSnapshottedWithoutLosingExecutionFacts() {
    var result = BatchResult.fromReports(2, List.of(
        new BatchResult.Report(0, 1, 1, true, -1, new int[] {1}, false, false, 0),
        new BatchResult.Report(1, 1, 1, true, -1, new int[] {Statement.EXECUTE_FAILED}, false, false, 0)));
    var rows = new ArrayList<>(Arrays.asList(null, "returned"));
    var partial = result.withRows(rows);
    rows.clear();
    assertEquals(Arrays.asList(null, "returned"), partial.rows());
    assertSame(result.items(), partial.items());
    assertEquals(result.chunks(), partial.chunks());
    assertEquals(result.knownAffectedRows(), partial.knownAffectedRows());
    assertEquals(result.affectedRowsKnown(), partial.affectedRowsKnown());
    assertThrows(UnsupportedOperationException.class, () -> partial.rows().clear());
  }
}
