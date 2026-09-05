package io.skis.query;

import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.OrderDirection;
import java.util.Objects;

/** Immutable ordering item produced by a generated query column. */
public final class SortSpecification<E> {

  private final QueryColumn<E, ?> column;
  private final SortDirection direction;
  private final NullPlacement nullPlacement;

  SortSpecification(
      QueryColumn<E, ?> column, SortDirection direction, NullPlacement nullPlacement) {
    this.column = Objects.requireNonNull(column, "column");
    this.direction = Objects.requireNonNull(direction, "direction");
    this.nullPlacement = Objects.requireNonNull(nullPlacement, "nullPlacement");
  }

  /** Returns an equivalent item with null values ordered first. */
  public SortSpecification<E> nullsFirst() {
    return withNullPlacement(NullPlacement.FIRST);
  }

  /** Returns an equivalent item with null values ordered last. */
  public SortSpecification<E> nullsLast() {
    return withNullPlacement(NullPlacement.LAST);
  }

  public SortDirection direction() {
    return direction;
  }

  public NullPlacement nullPlacement() {
    return nullPlacement;
  }

  QueryColumn<E, ?> column() {
    return column;
  }

  OrderByItem ast() {
    return new OrderByItem(
        column.expression(),
        direction == SortDirection.ASC ? OrderDirection.ASC : OrderDirection.DESC,
        switch (nullPlacement) {
          case DIALECT_DEFAULT -> NullOrder.DIALECT_DEFAULT;
          case FIRST -> NullOrder.FIRST;
          case LAST -> NullOrder.LAST;
        });
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof SortSpecification<?> specification
            && column.expression().equals(specification.column.expression())
            && direction == specification.direction
            && nullPlacement == specification.nullPlacement;
  }

  @Override
  public int hashCode() {
    return Objects.hash(column.expression(), direction, nullPlacement);
  }

  @Override
  public String toString() {
    return "SortSpecification[property="
        + column.property().name()
        + ", direction="
        + direction
        + ", nullPlacement="
        + nullPlacement
        + ']';
  }

  private SortSpecification<E> withNullPlacement(NullPlacement placement) {
    return placement == nullPlacement
        ? this
        : new SortSpecification<>(column, direction, placement);
  }
}
