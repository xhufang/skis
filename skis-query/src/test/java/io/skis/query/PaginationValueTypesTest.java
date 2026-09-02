package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.sql.ast.SqlType;
import java.util.ArrayList;
import java.util.List;
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
  void requestAndSingleRowContractsRejectInvalidAmbiguity() {
    assertThrows(IllegalArgumentException.class, () -> PageRequest.page(-1, 10));
    assertThrows(IllegalArgumentException.class, () -> PageRequest.page(0, 0));
    assertEquals(
        (long) Integer.MAX_VALUE * Integer.MAX_VALUE,
        PageRequest.page(Integer.MAX_VALUE, Integer.MAX_VALUE).offset());
    assertThrows(IllegalArgumentException.class, () -> SliceRequest.offset(-1, 10));
    assertThrows(IllegalArgumentException.class, () -> SliceRequest.keysetFirst(0));

    SingleRow<String> noRow = SingleRow.noRow();
    SingleRow<String> presentNull = SingleRow.present(null);
    assertTrue(noRow instanceof SingleRow.NoRow<?>);
    assertTrue(presentNull instanceof SingleRow.Present<?> present && present.value() == null);
  }

  @Test
  void closeableStreamClosesOnExhaustionAndExplicitShortCircuit() {
    TestCursor exhaustedCursor = new TestCursor(List.of("one", "two"));
    CloseableQueryStream<String> exhausted = new CloseableQueryStream<>(exhaustedCursor);

    assertEquals(List.of("one", "two"), exhausted.stream().toList());
    assertTrue(exhausted.isClosed());
    assertEquals(1, exhaustedCursor.closeCount);

    TestCursor shortCursor = new TestCursor(List.of("one", "two"));
    try (CloseableQueryStream<String> shortStream = new CloseableQueryStream<>(shortCursor)) {
      assertEquals("one", shortStream.stream().findFirst().orElseThrow());
      assertFalse(shortStream.isClosed());
    }
    assertTrue(shortCursor.isClosed());
    assertEquals(1, shortCursor.closeCount);
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
