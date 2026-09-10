package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.Identifier;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class QueryValueSnapshotsTest {

  private static final PropertyMeta<SnapshotRow, byte[]> BYTES =
      new PropertyMeta<>(0, "bytes", byte[].class, ColumnMeta.of("bytes_value", false));
  private static final PropertyMeta<SnapshotRow, Timestamp> TIMESTAMP =
      new PropertyMeta<>(
          1, "timestamp", Timestamp.class, ColumnMeta.of("timestamp_value", false));
  private static final PropertyMeta<SnapshotRow, Date> DATE =
      new PropertyMeta<>(2, "date", Date.class, ColumnMeta.of("date_value", false));
  private static final PropertyMeta<SnapshotRow, Time> TIME =
      new PropertyMeta<>(3, "time", Time.class, ColumnMeta.of("time_value", false));
  private static final EntityMeta<SnapshotRow> SNAPSHOT_ROW =
      EntityMeta.simple(
          SnapshotRow.class,
          new TableMeta("", "test", "snapshot_row"),
          List.of(BYTES, TIMESTAMP, DATE, TIME),
          null,
          true);
  private static final SnapshotTable TABLE = new SnapshotTable();

  @Test
  void capturesBinaryComparisonValueWhenThePredicateIsCreated() {
    byte[] source = {1, 2};
    QueryPredicate<SnapshotRow> predicate = TABLE.bytes().eq(source);

    source[0] = 9;
    byte[] firstCompilation = (byte[]) predicate.compile().arguments().getFirst();
    byte[] secondCompilation = (byte[]) predicate.compile().arguments().getFirst();

    assertArrayEquals(new byte[] {1, 2}, firstCompilation);
    assertNotSame(source, firstCompilation);
    assertSame(firstCompilation, secondCompilation);
  }

  @Test
  void capturesEveryBinaryMembershipElementWhenThePredicateIsCreated() {
    byte[] first = {1, 2};
    byte[] second = {3, 4};
    QueryPredicate<SnapshotRow> predicate = TABLE.bytes().in(List.of(first, second));

    first[0] = 9;
    second[0] = 8;
    List<@Nullable Object> captured = predicate.compile().arguments();

    assertArrayEquals(new byte[] {1, 2}, (byte[]) captured.get(0));
    assertArrayEquals(new byte[] {3, 4}, (byte[]) captured.get(1));
    assertNotSame(first, captured.get(0));
    assertNotSame(second, captured.get(1));
  }

  @Test
  void capturesSqlTimestampWithNanosecondPrecisionWhenThePredicateIsCreated() {
    Timestamp source = Timestamp.valueOf("2026-09-06 12:34:56.123456789");
    Timestamp expected = (Timestamp) source.clone();
    QueryPredicate<SnapshotRow> predicate = TABLE.timestamp().eq(source);

    source.setTime(0);
    source.setNanos(7);
    Timestamp captured = (Timestamp) predicate.compile().arguments().getFirst();

    assertEquals(expected, captured);
    assertEquals(expected.getNanos(), captured.getNanos());
    assertNotSame(source, captured);
  }

  @Test
  void capturesMutableSqlDateAndTimeValuesWhenPredicatesAreCreated() {
    Date date = Date.valueOf("2026-09-06");
    Time time = Time.valueOf("12:34:56");
    Date expectedDate = (Date) date.clone();
    Time expectedTime = (Time) time.clone();
    QueryPredicate<SnapshotRow> datePredicate = TABLE.date().eq(date);
    QueryPredicate<SnapshotRow> timePredicate = TABLE.time().eq(time);

    date.setTime(0);
    time.setTime(0);

    assertEquals(expectedDate, datePredicate.compile().arguments().getFirst());
    assertEquals(expectedTime, timePredicate.compile().arguments().getFirst());
  }

  private record SnapshotRow(byte[] bytes, Timestamp timestamp, Date date, Time time) {}

  private static final class SnapshotTable extends QueryTable<SnapshotRow> {

    private final NonNullQueryColumn<SnapshotRow, byte[]> bytes = nonNullQueryColumn(BYTES);
    private final NonNullQueryColumn<SnapshotRow, Timestamp> timestamp =
        nonNullQueryColumn(TIMESTAMP);
    private final NonNullQueryColumn<SnapshotRow, Date> date = nonNullQueryColumn(DATE);
    private final NonNullQueryColumn<SnapshotRow, Time> time = nonNullQueryColumn(TIME);

    private SnapshotTable() {
      super(SNAPSHOT_ROW);
    }

    private SnapshotTable(Identifier alias) {
      super(SNAPSHOT_ROW, alias);
    }

    private NonNullQueryColumn<SnapshotRow, byte[]> bytes() {
      return bytes;
    }

    private NonNullQueryColumn<SnapshotRow, Timestamp> timestamp() {
      return timestamp;
    }

    private NonNullQueryColumn<SnapshotRow, Date> date() {
      return date;
    }

    private NonNullQueryColumn<SnapshotRow, Time> time() {
      return time;
    }

    @Override
    public SnapshotTable as(String alias) {
      return new SnapshotTable(Identifier.of(alias));
    }

    @Override
    public SnapshotTable as(Identifier alias) {
      return new SnapshotTable(alias);
    }
  }
}
