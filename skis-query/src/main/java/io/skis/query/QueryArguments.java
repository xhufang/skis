package io.skis.query;

import java.util.AbstractList;
import java.util.Objects;
import java.util.RandomAccess;
import org.jspecify.annotations.Nullable;

/**
 * Immutable invocation values indexed by the dense logical slots of one final SQL statement.
 *
 * <p>The base segment is projected once from {@link QueryParameters}. Pagination values are kept in
 * a separate tail segment, so changing an offset or keyset does not copy the statement's ordinary
 * parameter table. Values have already crossed their capture boundary and are never snapshotted
 * again here.
 */
final class QueryArguments extends AbstractList<@Nullable Object> implements RandomAccess {

  private static final @Nullable Object[] NO_VALUES = new @Nullable Object[0];
  private static final QueryArguments EMPTY =
      new QueryArguments(NO_VALUES, NO_VALUES, QueryPaginationShape.none());

  private final @Nullable Object[] ordinaryValues;
  private final @Nullable Object[] paginationValues;
  private final QueryPaginationShape paginationShape;
  private final int size;

  private QueryArguments(
      @Nullable Object[] ordinaryValues,
      @Nullable Object[] paginationValues,
      QueryPaginationShape paginationShape) {
    this.ordinaryValues = Objects.requireNonNull(ordinaryValues, "ordinaryValues");
    this.paginationValues = Objects.requireNonNull(paginationValues, "paginationValues");
    this.paginationShape = Objects.requireNonNull(paginationShape, "paginationShape");
    this.size = Math.addExact(ordinaryValues.length, paginationValues.length);
    if (paginationValues.length != paginationValueCount(paginationShape)) {
      throw new IllegalArgumentException(
          "pagination values do not match their value-free pagination shape");
    }
  }

  /** Takes ownership of a freshly projected array that will not be mutated by its caller. */
  static QueryArguments fromProjectedValues(@Nullable Object[] values) {
    Objects.requireNonNull(values, "values");
    return values.length == 0
        ? EMPTY
        : new QueryArguments(values, NO_VALUES, QueryPaginationShape.none());
  }

  /** Creates a value-shaped carrier used only while compiling a plan without executing it. */
  static QueryArguments placeholders(int size) {
    if (size < 0) {
      throw new IllegalArgumentException("placeholder count must not be negative");
    }
    return size == 0
        ? EMPTY
        : new QueryArguments(
            new @Nullable Object[size], NO_VALUES, QueryPaginationShape.none());
  }

  /**
   * Appends exactly the value slots retained by the final pagination AST.
   *
   * <p>Null keyset anchors become {@code IS NULL}/{@code IS NOT NULL} predicates and therefore do
   * not occupy a logical parameter slot.
   */
  QueryArguments withPagination(QueryPagination pagination) {
    QueryPaginationShape shape =
        QueryPaginationShape.from(Objects.requireNonNull(pagination, "pagination"));
    return switch (pagination) {
      case QueryPagination.None ignored ->
          paginationShape.equals(shape)
              ? this
              : new QueryArguments(ordinaryValues, NO_VALUES, shape);
      case QueryPagination.LimitOnly limit ->
          withPaginationValues(new Object[] {limit.limit()}, shape);
      case QueryPagination.Offset offset ->
          withPaginationValues(new Object[] {offset.limit(), offset.offset()}, shape);
      case QueryPagination.Keyset keyset -> withPaginationValues(keysetTail(keyset), shape);
    };
  }

  /** Returns the execution argument expected by an internal compiled query plan. */
  Object planArgument() {
    return isEmpty() ? NoParameters.INSTANCE : this;
  }

  int ordinarySize() {
    return ordinaryValues.length;
  }

  QueryPaginationShape paginationShape() {
    return paginationShape;
  }

  @Override
  public @Nullable Object get(int index) {
    Objects.checkIndex(index, size);
    return index < ordinaryValues.length
        ? ordinaryValues[index]
        : paginationValues[index - ordinaryValues.length];
  }

  @Override
  public int size() {
    return size;
  }

  /** Never expose captured values through diagnostics. */
  @Override
  public String toString() {
    return "QueryArguments[size=" + size + ", values=<redacted>]";
  }

  private QueryArguments withPaginationValues(
      @Nullable Object[] tail, QueryPaginationShape shape) {
    return new QueryArguments(ordinaryValues, tail, shape);
  }

  private static int paginationValueCount(QueryPaginationShape shape) {
    return switch (shape.mode()) {
      case NONE -> 0;
      case LIMIT -> 1;
      case OFFSET -> 2;
      case KEYSET -> {
        int valueCount = 1;
        for (boolean nullMarker : shape.keysetNullMarkers()) {
          if (!nullMarker) {
            valueCount = Math.addExact(valueCount, 1);
          }
        }
        yield valueCount;
      }
    };
  }

  private static @Nullable Object[] keysetTail(QueryPagination.Keyset keyset) {
    int valueCount = 0;
    for (@Nullable Object value : keyset.values()) {
      if (value != null) {
        valueCount++;
      }
    }
    @Nullable Object[] tail = new @Nullable Object[valueCount + 1];
    int target = 0;
    for (@Nullable Object value : keyset.values()) {
      if (value != null) {
        tail[target++] = value;
      }
    }
    tail[target] = keyset.limit();
    return tail;
  }
}
