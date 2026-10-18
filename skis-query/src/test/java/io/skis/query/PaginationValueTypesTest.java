package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.jdbc.internal.JdbcCursor;
import io.skis.sql.ast.SqlType;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PaginationValueTypesTest {

  @Test
  void pageDefensivelyCopiesNullableItemsAndComputesMetadata() {
    List<@Nullable String> source = new ArrayList<>();
    source.add("Mimi");
    source.add(null);

    Page<@Nullable String> page = Page.of(source, PageRequest.page(1, 2), 5);
    source.clear();

    assertEquals(java.util.Arrays.asList("Mimi", null), page.items());
    assertEquals(3, page.totalPages());
    assertTrue(page.hasNext());
    assertTrue(page.hasPrevious());
    assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
  }

  @Test
  void sliceAndContinuationAreImmutableAndRedactAnchorValues() {
    List<Object> anchors = new ArrayList<>();
    anchors.add("private-name");
    SliceContinuation continuation =
        SliceContinuation.keyset(
            "query", "order", List.of(SqlType.VARCHAR), List.of(false), anchors, "parameters");
    anchors.clear();
    Slice<String> slice = Slice.of(List.of("Mimi"), 1, continuation);

    assertEquals(List.of("private-name"), continuation.keysetValues());
    assertFalse(continuation.toString().contains("private-name"));
    assertTrue(slice.hasNext());
    assertEquals(continuation, slice.nextContinuation().orElseThrow());
    assertThrows(UnsupportedOperationException.class, () -> continuation.keysetValues().clear());
  }

  @Test
  void continuationDeepCopiesAndComparesArrayAnchorsByValue() {
    byte[] source = {1, 2};
    SliceContinuation first =
        SliceContinuation.keyset(
            "query",
            "order",
            List.of(SqlType.VARBINARY),
            List.of(false),
            List.of(source),
            "parameters");
    source[0] = 9;
    SliceContinuation sameValue =
        SliceContinuation.keyset(
            "query",
            "order",
            List.of(SqlType.VARBINARY),
            List.of(false),
            List.of(new byte[] {1, 2}),
            "parameters");

    assertArrayEquals(new byte[] {1, 2}, (byte[]) first.keysetValues().getFirst());
    assertEquals(first, sameValue);
    assertEquals(first.hashCode(), sameValue.hashCode());
  }

  @Test
  void continuationCopiesMutableSqlTimestampAnchorsOnInputAndOutput() {
    Timestamp source = Timestamp.valueOf("2026-09-06 12:34:56.123456789");
    Timestamp expected = (Timestamp) source.clone();
    SliceContinuation continuation =
        SliceContinuation.keyset(
            "query",
            "order",
            List.of(SqlType.TIMESTAMP),
            List.of(false),
            List.of(source),
            "parameters");

    source.setTime(0);
    Timestamp firstRead = (Timestamp) continuation.keysetValues().getFirst();
    firstRead.setTime(1);

    assertEquals(expected, continuation.keysetValues().getFirst());
  }

  @Test
  void requestAndSingleRowContractsRejectInvalidAmbiguity() {
    assertThrows(QueryValidationException.class, () -> PageRequest.page(-1, 10));
    assertThrows(QueryValidationException.class, () -> PageRequest.page(0, 0));
    assertEquals(
        (long) Integer.MAX_VALUE * Integer.MAX_VALUE,
        PageRequest.page(Integer.MAX_VALUE, Integer.MAX_VALUE).offset());
    assertThrows(QueryValidationException.class, () -> SliceRequest.offset(-1, 10));
    assertThrows(QueryValidationException.class, () -> SliceRequest.keysetFirst(0));

    SingleRow<String> noRow = SingleRow.noRow();
    SingleRow<@Nullable String> presentNull = SingleRow.present(null);
    assertTrue(!noRow.hasRow());
    assertTrue(presentNull.hasRow() && presentNull.isNull());
  }

  @Test
  void singleRowPreservesRowStateWhileItsValueOperationsFollowOptionalSemantics() {
    SingleRow<String> noRow = SingleRow.noRow();
    SingleRow<String> empty = SingleRow.empty();
    SingleRow<@Nullable String> nullRow = SingleRow.present(null);
    SingleRow<String> valueRow = SingleRow.of("Mimi");

    assertSame(noRow, empty);
    assertFalse(noRow.hasRow());
    assertFalse(noRow.isNull());
    assertTrue(noRow.isEmpty());
    assertTrue(nullRow.hasRow());
    assertTrue(nullRow.isNull());
    assertTrue(nullRow.isEmpty());
    assertTrue(valueRow.hasRow());
    assertTrue(valueRow.isPresent());
    assertFalse(valueRow.isEmpty());

    assertThrows(NoSuchElementException.class, noRow::get);
    assertThrows(NoSuchElementException.class, nullRow::orElseThrow);
    assertThrows(NullPointerException.class, () -> SingleRow.of(null));
    assertSame(noRow, SingleRow.ofNullable(null));
    assertEquals(SingleRow.of("Mimi"), SingleRow.ofNullable("Mimi"));

    AtomicInteger fallbacks = new AtomicInteger();
    assertEquals("Mimi", valueRow.orElseGet(() -> fallback(fallbacks, "other")));
    assertEquals(0, fallbacks.get());
    assertEquals("other", noRow.orElseGet(() -> fallback(fallbacks, "other")));
    assertEquals(1, fallbacks.get());
    assertNull(nullRow.orElse(null));
    assertEquals("Mimi", valueRow.orElseGet(null));
    assertEquals("Mimi", valueRow.orElseThrow(null));
    assertThrows(NullPointerException.class, () -> noRow.orElseGet(null));
    assertThrows(NullPointerException.class, () -> noRow.orElseThrow(null));

    assertEquals(Optional.of(4), valueRow.map(String::length));
    assertEquals(Optional.empty(), nullRow.map(String::length));
    assertEquals(Optional.of("Mimi"), valueRow.filter(value -> value.startsWith("M")));
    assertEquals(Optional.empty(), valueRow.filter(value -> value.startsWith("A")));
    assertEquals(Optional.of(4), valueRow.flatMap(value -> Optional.of(value.length())));
    assertEquals(Optional.of("fallback"), noRow.or(() -> Optional.of("fallback")));
    assertThrows(NullPointerException.class, () -> noRow.filter(null));
    assertThrows(NullPointerException.class, () -> noRow.map(null));
    assertThrows(NullPointerException.class, () -> noRow.flatMap(null));
    assertThrows(NullPointerException.class, () -> noRow.or(null));
    assertThrows(NullPointerException.class, () -> valueRow.flatMap(value -> null));
    assertThrows(NullPointerException.class, () -> noRow.or(() -> null));
    assertEquals(List.of("Mimi"), valueRow.stream().toList());
    assertEquals(List.of(), nullRow.stream().toList());

    AtomicReference<String> accepted = new AtomicReference<>();
    AtomicInteger emptyActions = new AtomicInteger();
    valueRow.ifPresent(accepted::set);
    nullRow.ifPresentOrElse(accepted::set, emptyActions::incrementAndGet);
    noRow.ifPresent(null);
    valueRow.ifPresentOrElse(accepted::set, null);
    noRow.ifPresentOrElse(null, emptyActions::incrementAndGet);
    assertEquals("Mimi", accepted.get());
    assertEquals(2, emptyActions.get());
    assertThrows(NullPointerException.class, () -> valueRow.ifPresent(null));
    assertThrows(NullPointerException.class, () -> noRow.ifPresentOrElse(accepted::set, null));

    assertNotEquals(noRow, nullRow);
    assertNotEquals(noRow.hashCode(), nullRow.hashCode());
    SingleRow<Object> redacted = SingleRow.of(new ThrowingToString());
    assertEquals("SingleRow[present]", redacted.toString());
  }

  @Test
  void jdbcCursorRejectsCrossThreadReadAndCloseBeforeTouchingTheDelegate() throws Exception {
    CountingJdbcCursor delegate = new CountingJdbcCursor();
    DefaultQueryCursor<String> cursor = new DefaultQueryCursor<>(delegate, false);
    AtomicReference<Throwable> readFailure = new AtomicReference<>();
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();

    Thread wrongThread =
        new Thread(
            () -> {
              try {
                cursor.advance();
              } catch (Throwable failure) {
                readFailure.set(failure);
              }
              try {
                cursor.close();
              } catch (Throwable failure) {
                closeFailure.set(failure);
              }
            });
    wrongThread.start();
    wrongThread.join();

    QueryValidationException readViolation =
        assertInstanceOf(QueryValidationException.class, readFailure.get());
    QueryValidationException closeViolation =
        assertInstanceOf(QueryValidationException.class, closeFailure.get());
    assertEquals(SkisErrorCode.QUERY_CURSOR_THREAD, readViolation.code());
    assertEquals(SkisErrorCode.QUERY_CURSOR_THREAD, closeViolation.code());
    assertEquals(0, delegate.advanceCount);
    assertEquals(0, delegate.closeCount);

    cursor.close();
    assertEquals(1, delegate.closeCount);
  }

  @Test
  void queryStreamDoesNotSplitAndItsSpliteratorRetainsCursorThreadOwnership() throws Exception {
    CountingJdbcCursor delegate = new CountingJdbcCursor();
    Stream<String> stream =
        QueryStreams.open(new DefaultQueryCursor<>(delegate, false)).parallel();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    assertTrue(stream.isParallel());
    Spliterator<String> source = stream.spliterator();
    assertNull(source.trySplit());
    Thread wrongThread =
        new Thread(
            () -> {
              try {
                source.tryAdvance(ignored -> {});
              } catch (Throwable caught) {
                failure.set(caught);
              }
            });
    wrongThread.start();
    wrongThread.join();

    QueryValidationException violation =
        assertInstanceOf(QueryValidationException.class, failure.get());
    assertEquals(SkisErrorCode.QUERY_CURSOR_THREAD, violation.code());
    assertEquals(0, delegate.advanceCount);
    stream.close();
    assertEquals(1, delegate.closeCount);
  }

  @Test
  void queryStreamIteratorRetainsCursorThreadOwnership() throws Exception {
    CountingJdbcCursor delegate = new CountingJdbcCursor();
    Stream<String> stream = QueryStreams.open(new DefaultQueryCursor<>(delegate, false));
    Iterator<String> iterator = stream.iterator();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Thread wrongThread =
        new Thread(
            () -> {
              try {
                iterator.hasNext();
              } catch (Throwable caught) {
                failure.set(caught);
              }
            });
    wrongThread.start();
    wrongThread.join();

    QueryValidationException iteratorViolation =
        assertInstanceOf(QueryValidationException.class, failure.get());
    assertEquals(SkisErrorCode.QUERY_CURSOR_THREAD, iteratorViolation.code());
    assertEquals(0, delegate.advanceCount);
    stream.close();
    assertEquals(1, delegate.closeCount);
  }

  @Test
  void queryCursorRejectsRacingForeignReadAndCloseBeforeTouchingJdbc() throws Exception {
    CountingJdbcCursor delegate = new CountingJdbcCursor();
    DefaultQueryCursor<String> cursor = new DefaultQueryCursor<>(delegate, false);
    CyclicBarrier start = new CyclicBarrier(2);
    AtomicReference<Throwable> readFailure = new AtomicReference<>();
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();

    Thread reader =
        new Thread(
            () -> {
              try {
                start.await();
                cursor.advance();
              } catch (Throwable failure) {
                readFailure.set(failure);
              }
            });
    Thread closer =
        new Thread(
            () -> {
              try {
                start.await();
                cursor.close();
              } catch (Throwable failure) {
                closeFailure.set(failure);
              }
            });
    reader.start();
    closer.start();
    reader.join();
    closer.join();

    QueryValidationException readViolation =
        assertInstanceOf(QueryValidationException.class, readFailure.get());
    QueryValidationException closeViolation =
        assertInstanceOf(QueryValidationException.class, closeFailure.get());
    assertEquals(SkisErrorCode.QUERY_CURSOR_THREAD, readViolation.code());
    assertEquals(SkisErrorCode.QUERY_CURSOR_THREAD, closeViolation.code());
    assertEquals(0, delegate.advanceCount);
    assertEquals(0, delegate.closeCount);
    cursor.close();
    assertEquals(1, delegate.closeCount);
  }

  @Test
  void queryStreamClosesOnExhaustionAndExplicitShortCircuit() {
    TestCursor exhaustedCursor = new TestCursor(List.of("one", "two"));
    Stream<String> exhausted = QueryStreams.open(exhaustedCursor);

    assertEquals(List.of("one", "two"), exhausted.toList());
    assertTrue(exhaustedCursor.isClosed());
    assertEquals(1, exhaustedCursor.closeCount);

    TestCursor shortCursor = new TestCursor(List.of("one", "two"));
    try (Stream<String> shortStream = QueryStreams.open(shortCursor)) {
      assertEquals("one", shortStream.findFirst().orElseThrow());
      assertFalse(shortCursor.isClosed());
    }
    assertTrue(shortCursor.isClosed());
    assertEquals(1, shortCursor.closeCount);
  }

  @Test
  void queryStreamDoesNotRetryAFailedExhaustionClose() {
    RuntimeException closeFailure = new RuntimeException("close failed");
    AtomicInteger closeCount = new AtomicInteger();
    QueryCursor<String> cursor =
        new QueryCursor<>() {
          @Override
          public boolean advance() {
            return false;
          }

          @Override
          public String current() {
            throw new AssertionError("an exhausted cursor has no current row");
          }

          @Override
          public boolean isClosed() {
            return closeCount.get() != 0;
          }

          @Override
          public void close() {
            closeCount.incrementAndGet();
            throw closeFailure;
          }
        };

    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> QueryStreams.open(cursor).count());

    assertSame(closeFailure, thrown);
    assertEquals(1, closeCount.get());
  }

  @Test
  void queryStreamClosesAfterCursorFailureAndPreservesCloseFailure() {
    RuntimeException readFailure = new RuntimeException("read failed");
    RuntimeException closeFailure = new RuntimeException("close failed");
    AtomicInteger closeCount = new AtomicInteger();
    QueryCursor<String> cursor =
        new QueryCursor<>() {
          @Override
          public boolean advance() {
            return true;
          }

          @Override
          public String current() {
            throw readFailure;
          }

          @Override
          public boolean isClosed() {
            return closeCount.get() != 0;
          }

          @Override
          public void close() {
            closeCount.incrementAndGet();
            throw closeFailure;
          }
        };

    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> QueryStreams.open(cursor).findFirst());

    assertSame(readFailure, thrown);
    assertArrayEquals(new Throwable[] {closeFailure}, thrown.getSuppressed());
    assertEquals(1, closeCount.get());
  }

  private static String fallback(AtomicInteger calls, String value) {
    calls.incrementAndGet();
    return value;
  }

  private static final class ThrowingToString {

    @Override
    public String toString() {
      throw new AssertionError("SingleRow diagnostics must not stringify values");
    }
  }

  private static final class CountingJdbcCursor implements JdbcCursor<String> {

    private int advanceCount;
    private int closeCount;

    @Override
    public boolean advance() {
      advanceCount++;
      return false;
    }

    @Override
    public @Nullable String current() {
      return null;
    }

    @Override
    public boolean isClosed() {
      return closeCount != 0;
    }

    @Override
    public void close() {
      closeCount++;
    }
  }

  private static final class TestCursor implements QueryCursor<String> {

    private final List<String> values;
    private int index = -1;
    private int closeCount;
    private boolean closed;
    private @Nullable String current;

    private TestCursor(List<String> values) {
      this.values = List.copyOf(values);
    }

    @Override
    public boolean advance() {
      if (closed) {
        return false;
      }
      index++;
      if (index >= values.size()) {
        close();
        return false;
      }
      current = values.get(index);
      return true;
    }

    @Override
    public String current() {
      if (current == null || closed) {
        throw new IllegalStateException();
      }
      return current;
    }

    @Override
    public boolean isClosed() {
      return closed;
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        current = null;
        closeCount++;
      }
    }
  }
}
