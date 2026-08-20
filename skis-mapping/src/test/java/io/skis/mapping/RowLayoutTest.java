package io.skis.mapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RowLayoutTest {

  @Test
  void defensivelyCopiesIndexesAndRepresentsUnselectedProperties() {
    int[] indexes = {2, 0, 5};
    RowLayout layout = RowLayout.of(indexes);
    indexes[0] = 99;

    assertEquals(3, layout.propertyCount());
    assertEquals(2, layout.requireIndex(0));
    assertFalse(layout.contains(1));
    assertTrue(layout.contains(2));
    assertThrows(IllegalArgumentException.class, () -> layout.requireIndex(1));

    int[] copy = layout.toArray();
    copy[2] = 99;
    assertArrayEquals(new int[] {2, 0, 5}, layout.toArray());
  }

  @Test
  void createsContiguousLayoutsFromAnyPositiveJdbcIndex() {
    assertEquals(RowLayout.of(4, 5, 6), RowLayout.contiguous(3, 4));
    assertEquals(RowLayout.of(), RowLayout.contiguous(0, 1));
  }

  @Test
  void rejectsInvalidLayoutShapesAndOrdinals() {
    assertThrows(NullPointerException.class, () -> RowLayout.of((int[]) null));
    assertThrows(IllegalArgumentException.class, () -> RowLayout.of(1, -1));
    assertThrows(IllegalArgumentException.class, () -> RowLayout.contiguous(-1, 1));
    assertThrows(IllegalArgumentException.class, () -> RowLayout.contiguous(1, 0));
    assertThrows(ArithmeticException.class, () -> RowLayout.contiguous(2, Integer.MAX_VALUE));

    RowLayout layout = RowLayout.of(1);
    assertThrows(IndexOutOfBoundsException.class, () -> layout.index(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> layout.index(1));
  }
}
