package io.skis.mutation;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Detached, value-free partial execution facts; unknown counts are never replaced with one. */
public final class BatchResult {

  public enum Status {
    SUCCEEDED,
    FAILED,
    NOT_EXECUTED,
    UNKNOWN
  }

  public record Item(
      int inputIndex, int chunkIndex, Status status, boolean countKnown, long affectedRows) {

    public Item {
      Objects.requireNonNull(status);
      if (inputIndex < 0 || chunkIndex < -1 || affectedRows < 0 || !countKnown && affectedRows != 0)
        throw new IllegalArgumentException("invalid batch item facts");
    }
  }

  public record Chunk(
      int chunkIndex, int firstInput, int inputCount, boolean countKnown, long affectedRows) {

    public Chunk {
      if (chunkIndex < 0
          || firstInput < 0
          || inputCount < 1
          || affectedRows < 0
          || !countKnown && affectedRows != 0)
        throw new IllegalArgumentException("invalid batch chunk facts");
    }
  }

  private final List<Item> items;
  private final List<Chunk> chunks;
  private final long knownAffectedRows;
  private final boolean affectedRowsKnown;
  private final List<?> rows;

  /** Compact owned driver facts; arrays are snapshotted without expanding one object per input. */
  public record Report(
      int firstInput,
      int inputCount,
      int submitted,
      boolean executed,
      int failedInput,
      int[] counts,
      boolean aggregate,
      boolean aggregateKnown,
      long aggregateCount,
      boolean optimistic) {
    public Report(
        int firstInput,
        int inputCount,
        int submitted,
        boolean executed,
        int failedInput,
        int[] counts,
        boolean aggregate,
        boolean aggregateKnown,
        long aggregateCount) {
      this(
          firstInput,
          inputCount,
          submitted,
          executed,
          failedInput,
          counts,
          aggregate,
          aggregateKnown,
          aggregateCount,
          false);
    }

    public Report {
      if (firstInput < 0
          || inputCount < 1
          || submitted < 0
          || submitted > inputCount
          || failedInput < -1
          || failedInput >= inputCount
          || aggregateCount < 0) throw new IllegalArgumentException("invalid batch report");
      counts = counts.clone();
    }

    @Override
    @SuppressWarnings("unused")
    public int[] counts() {
      return counts.clone();
    }
  }

  private BatchResult(
      List<Item> items, List<Chunk> chunks, long knownAffectedRows, boolean known, List<?> rows) {
    this.items = items;
    this.chunks = List.copyOf(chunks);
    this.knownAffectedRows = knownAffectedRows;
    this.affectedRowsKnown = known;
    this.rows = rows;
  }

  public static BatchResult fromReports(int inputCount, List<Report> reports) {
    if (inputCount < 0) throw new IllegalArgumentException("negative batch input count");
    List<Report> owned = List.copyOf(reports);
    List<Chunk> chunks = new ArrayList<>(owned.size());
    long total = 0;
    boolean allKnown = true;
    int next = 0;
    for (int index = 0; index < owned.size(); index++) {
      Report report = owned.get(index);
      if (report.firstInput != next || next > inputCount - report.inputCount)
        throw new IllegalArgumentException(
            "batch reports must be contiguous and within input bounds");
      next += report.inputCount;
      long known = 0;
      boolean exact = true;
      if (report.aggregate) {
        known = report.aggregateKnown ? report.aggregateCount : 0;
        exact = report.aggregateKnown;
      } else if (report.executed) {
        exact =
            report.counts.length == report.submitted && report.counts.length <= report.inputCount;
        for (int position = 0;
            position < Math.min(report.counts.length, report.submitted);
            position++) {
          int count = report.counts[position];
          if (count < 0) exact = false;
          else known += count;
        }
      }
      total += known;
      allKnown &= exact;
      chunks.add(new Chunk(index, report.firstInput, report.inputCount, exact, exact ? known : 0));
    }
    List<Item> items =
        Collections.unmodifiableList(
            new AbstractList<>() {
              @Override
              public int size() {
                return inputCount;
              }

              @Override
              public Item get(int index) {
                Objects.checkIndex(index, inputCount);
                int low = 0, high = owned.size() - 1;
                while (low <= high) {
                  int middle = (low + high) >>> 1;
                  Report report = owned.get(middle);
                  if (index < report.firstInput) high = middle - 1;
                  else if (index >= report.firstInput + report.inputCount) low = middle + 1;
                  else return item(report, middle, index - report.firstInput);
                }
                return new Item(index, -1, Status.NOT_EXECUTED, true, 0);
              }
            });
    return new BatchResult(items, chunks, total, allKnown, List.of());
  }

  private static Item item(Report report, int chunk, int position) {
    int index = report.firstInput + position;
    if (!report.executed || position >= report.submitted)
      return new Item(
          index,
          chunk,
          position == report.failedInput ? Status.FAILED : Status.NOT_EXECUTED,
          true,
          0);
    if (report.aggregate) {
      if (report.inputCount == 1 && report.aggregateKnown)
        return new Item(
            index,
            chunk,
            report.optimistic && report.aggregateCount == 0 ? Status.FAILED : Status.SUCCEEDED,
            true,
            report.aggregateCount);
      return new Item(
          index,
          chunk,
          report.aggregateKnown && !report.optimistic ? Status.SUCCEEDED : Status.UNKNOWN,
          false,
          0);
    }
    if (report.counts.length > report.inputCount || position >= report.counts.length)
      return new Item(index, chunk, Status.UNKNOWN, false, 0);
    int count = report.counts[position];
    return count >= 0
        ? new Item(
            index,
            chunk,
            report.optimistic && count == 0 ? Status.FAILED : Status.SUCCEEDED,
            true,
            count)
        : new Item(
            index,
            chunk,
            count == java.sql.Statement.SUCCESS_NO_INFO
                ? Status.SUCCEEDED
                : count == java.sql.Statement.EXECUTE_FAILED ? Status.FAILED : Status.UNKNOWN,
            false,
            0);
  }

  @SuppressWarnings("Java9CollectionFactory")
  public BatchResult withRows(List<?> returned) {
    return new BatchResult(
        items,
        chunks,
        knownAffectedRows,
        affectedRowsKnown,
        Collections.unmodifiableList(new ArrayList<>(returned)));
  }

  /**
   * Successfully detached rows from completed chunks; these have no implied input-order mapping.
   */
  public List<?> rows() {
    return rows;
  }

  public List<Item> items() {
    return items;
  }

  @SuppressWarnings("unused")
  public List<Chunk> chunks() {
    return chunks;
  }

  public long knownAffectedRows() {
    return knownAffectedRows;
  }

  public boolean affectedRowsKnown() {
    return affectedRowsKnown;
  }

  @Override
  public String toString() {
    return "BatchResult[inputs="
        + items.size()
        + ", chunks="
        + chunks.size()
        + ", values redacted]";
  }
}
