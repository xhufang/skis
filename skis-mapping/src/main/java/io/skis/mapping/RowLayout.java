package io.skis.mapping;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable mapping from entity property ordinals to one-based JDBC result-set column indexes.
 *
 * <p>An index of {@code 0} means that the property is not part of the current projection.
 */
public final class RowLayout {

  private final int[] indexesByOrdinal;

  private RowLayout(int[] indexesByOrdinal) {
    this.indexesByOrdinal = indexesByOrdinal;
  }

  /** Creates a layout from indexes ordered by property ordinal. */
  public static RowLayout of(int... indexesByOrdinal) {
    int[] copy = Objects.requireNonNull(indexesByOrdinal, "indexesByOrdinal").clone();
    for (int index : copy) {
      if (index < 0) {
        throw new IllegalArgumentException("a result-set index must not be negative");
      }
    }
    return new RowLayout(copy);
  }

  /** Creates a contiguous full-row layout. */
  public static RowLayout contiguous(int propertyCount, int firstColumnIndex) {
    if (propertyCount < 0) {
      throw new IllegalArgumentException("propertyCount must not be negative");
    }
    if (firstColumnIndex < 1) {
      throw new IllegalArgumentException("firstColumnIndex must be positive");
    }
    int[] indexes = new int[propertyCount];
    for (int ordinal = 0; ordinal < propertyCount; ordinal++) {
      indexes[ordinal] = Math.addExact(firstColumnIndex, ordinal);
    }
    return new RowLayout(indexes);
  }

  /** Returns the number of entity properties represented by this layout. */
  public int propertyCount() {
    return indexesByOrdinal.length;
  }

  /** Returns the JDBC column index, or {@code 0} if the property was not selected. */
  public int index(int ordinal) {
    checkOrdinal(ordinal);
    return indexesByOrdinal[ordinal];
  }

  /** Returns a selected JDBC column index. */
  public int requireIndex(int ordinal) {
    int index = index(ordinal);
    if (index == 0) {
      throw new IllegalArgumentException("property ordinal " + ordinal + " is not selected");
    }
    return index;
  }

  /** Returns whether the property is part of the projection. */
  public boolean contains(int ordinal) {
    return index(ordinal) != 0;
  }

  /** Returns a defensive copy ordered by property ordinal. */
  public int[] toArray() {
    return indexesByOrdinal.clone();
  }

  private void checkOrdinal(int ordinal) {
    if (ordinal < 0 || ordinal >= indexesByOrdinal.length) {
      throw new IndexOutOfBoundsException(
          "property ordinal " + ordinal + " is outside [0, " + indexesByOrdinal.length + ")");
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof RowLayout layout
        && Arrays.equals(indexesByOrdinal, layout.indexesByOrdinal);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(indexesByOrdinal);
  }

  @Override
  public String toString() {
    return "RowLayout" + Arrays.toString(indexesByOrdinal);
  }
}
