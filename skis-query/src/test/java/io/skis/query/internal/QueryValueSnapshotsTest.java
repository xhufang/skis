package io.skis.query.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.core.QueryValidationException;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.QueryCondition;
import io.skis.query.QueryParameter;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.SliceContinuation;
import io.skis.query.SliceRequest;
import io.skis.query.Sql;
import java.lang.reflect.Proxy;
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
      new PropertyMeta<>(1, "timestamp", Timestamp.class, ColumnMeta.of("timestamp_value", false));
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
  void rejectsForeignDslObjectsBeforeInvokingTheirMethods() {
    NonNullSelectable<byte[]> nonNull = foreign(NonNullSelectable.class);
    Selectable<byte[]> selectable = foreign(Selectable.class);
    ProjectionSelection<SnapshotRow> projection = foreign(ProjectionSelection.class);
    QueryParameter<byte[]> parameter = foreign(QueryParameter.class);
    QueryCondition condition = foreign(QueryCondition.class);
    SliceRequest request = foreign(SliceRequest.class);
    SliceContinuation continuation = foreign(SliceContinuation.class);

    assertThrows(QueryValidationException.class, () -> Sql.select(nonNull));
    assertThrows(QueryValidationException.class, () -> Sql.select(selectable));
    assertThrows(QueryValidationException.class, () -> Sql.select(projection));
    assertThrows(QueryValidationException.class, () -> Sql.output(selectable, "value"));
    assertThrows(QueryValidationException.class, () -> Sql.output(nonNull, "value"));
    assertThrows(QueryValidationException.class, () -> TABLE.bytes().eq(selectable));
    assertThrows(QueryValidationException.class, () -> TABLE.bytes().eq(parameter));
    assertThrows(QueryValidationException.class, () -> Sql.selectFrom(TABLE).where(condition));
    assertThrows(QueryValidationException.class, () -> TABLE.bytes().isNotNull().and(condition));
    assertThrows(QueryValidationException.class, () -> QueryModels.sliceRequest(request));
    assertThrows(QueryValidationException.class, () -> SliceRequest.resume(continuation, 10));
    assertThrows(
        QueryValidationException.class,
        () -> Sql.selectFrom(TABLE).join(TABLE.as("other")).on(condition));
  }

  @SuppressWarnings("unchecked")
  private static <T> T foreign(Class<?> contract) {
    return (T)
        Proxy.newProxyInstance(
            contract.getClassLoader(),
            new Class<?>[] {contract},
            (proxy, method, arguments) -> {
              throw new AssertionError("Foreign DSL method must not be invoked: " + method);
            });
  }

  @Test
  void capturesBinaryComparisonValueWhenThePredicateIsCreated() {
    byte[] source = {1, 2};
    QueryCondition predicate = TABLE.bytes().eq(source);

    source[0] = 9;
    byte[] firstCompilation = (byte[]) arguments(predicate).getFirst();
    byte[] secondCompilation = (byte[]) arguments(predicate).getFirst();

    assertArrayEquals(new byte[] {1, 2}, firstCompilation);
    assertNotSame(source, firstCompilation);
    assertSame(firstCompilation, secondCompilation);
  }

  @Test
  void capturesEveryBinaryMembershipElementWhenThePredicateIsCreated() {
    byte[] first = {1, 2};
    byte[] second = {3, 4};
    QueryCondition predicate = TABLE.bytes().in(List.of(first, second));

    first[0] = 9;
    second[0] = 8;
    List<@Nullable Object> captured = arguments(predicate);

    assertArrayEquals(new byte[] {1, 2}, (byte[]) captured.get(0));
    assertArrayEquals(new byte[] {3, 4}, (byte[]) captured.get(1));
    assertNotSame(first, captured.get(0));
    assertNotSame(second, captured.get(1));
  }

  @Test
  void capturesSqlTimestampWithNanosecondPrecisionWhenThePredicateIsCreated() {
    Timestamp source = Timestamp.valueOf("2026-09-06 12:34:56.123456789");
    Timestamp expected = (Timestamp) source.clone();
    QueryCondition predicate = TABLE.timestamp().eq(source);

    source.setTime(0);
    source.setNanos(7);
    Timestamp captured = (Timestamp) arguments(predicate).getFirst();

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
    QueryCondition datePredicate = TABLE.date().eq(date);
    QueryCondition timePredicate = TABLE.time().eq(time);

    date.setTime(0);
    time.setTime(0);

    assertEquals(expectedDate, arguments(datePredicate).getFirst());
    assertEquals(expectedTime, arguments(timePredicate).getFirst());
  }

  @Test
  void preservesNestedArrayTypesAndNullElementsWhileDetachingMutableValues() {
    byte[][] source = {new byte[] {1, 2}, null, new byte[0]};
    byte[][] captured = (byte[][]) QueryValueSnapshots.copy(source);

    source[0][0] = 9;

    assertNotSame(source, captured);
    assertEquals(source.getClass(), captured.getClass());
    assertArrayEquals(new byte[] {1, 2}, captured[0]);
    assertNotSame(source[0], captured[0]);
    assertNull(captured[1]);
    assertArrayEquals(new byte[0], captured[2]);
    assertNotSame(source[2], captured[2]);
  }

  @Test
  void snapshotsAllPrimitiveArrayKindsWithoutChangingTheirRuntimeType() {
    List<Object> values =
        List.of(
            new boolean[] {true},
            new byte[] {1},
            new short[] {2},
            new char[] {'x'},
            new int[] {3},
            new long[] {4},
            new float[] {Float.NaN, -0.0f},
            new double[] {Double.NaN, -0.0d});
    for (Object value : values) {
      Object snapshot = QueryValueSnapshots.copy(value);
      assertNotSame(value, snapshot);
      assertEquals(value.getClass(), snapshot.getClass());
      org.junit.jupiter.api.Assertions.assertTrue(java.util.Objects.deepEquals(value, snapshot));
    }
    Timestamp timestamp = Timestamp.valueOf("2026-10-21 12:00:00.123456789");
    Timestamp[][] matrix = {{timestamp}, null, {}};
    Timestamp[][] snapshot = (Timestamp[][]) QueryValueSnapshots.copy(matrix);
    assertEquals(matrix.getClass(), snapshot.getClass());
    assertNotSame(timestamp, snapshot[0][0]);
    assertEquals(timestamp.getNanos(), snapshot[0][0].getNanos());
    timestamp.setTime(0);
    org.junit.jupiter.api.Assertions.assertNotEquals(timestamp, snapshot[0][0]);
    assertNull(snapshot[1]);
  }

  private static List<@Nullable Object> arguments(QueryCondition condition) {
    return QueryTestSupport.arguments(QueryTestSupport.compile(TABLE, List.of(), condition));
  }

  private record SnapshotRow(byte[] bytes, Timestamp timestamp, Date date, Time time) {}

  private static final class SnapshotTable extends QueryTable<SnapshotRow> {

    private final NonNullSelectable<byte[]> bytes = nonNullQueryColumn(BYTES);
    private final NonNullSelectable<Timestamp> timestamp = nonNullQueryColumn(TIMESTAMP);
    private final NonNullSelectable<Date> date = nonNullQueryColumn(DATE);
    private final NonNullSelectable<Time> time = nonNullQueryColumn(TIME);

    private SnapshotTable() {
      super(SNAPSHOT_ROW);
    }

    private SnapshotTable(String alias) {
      super(SNAPSHOT_ROW, alias);
    }

    private NonNullSelectable<byte[]> bytes() {
      return bytes;
    }

    private NonNullSelectable<Timestamp> timestamp() {
      return timestamp;
    }

    private NonNullSelectable<Date> date() {
      return date;
    }

    private NonNullSelectable<Time> time() {
      return time;
    }

    @Override
    public SnapshotTable as(String alias) {
      return new SnapshotTable(alias);
    }
  }
}
