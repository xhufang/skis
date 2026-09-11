package io.skis.query;

import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.OrderDirection;
import io.skis.sql.ast.SqlExpression;
import java.util.Objects;

/** Immutable ordering item over one framework-owned selectable expression. */
public final class SortSpecification {

  private final Selectable<?> selectable;
  private final SortDirection direction;
  private final NullPlacement nullPlacement;

  SortSpecification(
      Selectable<?> selectable, SortDirection direction, NullPlacement nullPlacement) {
    this.selectable = Objects.requireNonNull(selectable, "selectable");
    this.direction = Objects.requireNonNull(direction, "direction");
    this.nullPlacement = Objects.requireNonNull(nullPlacement, "nullPlacement");
  }

  /** Returns an equivalent item with null values ordered first. */
  public SortSpecification nullsFirst() {
    return withNullPlacement(NullPlacement.FIRST);
  }

  /** Returns an equivalent item with null values ordered last. */
  public SortSpecification nullsLast() {
    return withNullPlacement(NullPlacement.LAST);
  }

  public SortDirection direction() {
    return direction;
  }

  public NullPlacement nullPlacement() {
    return nullPlacement;
  }

  Selectable<?> selectable() {
    return selectable;
  }

  SqlExpression<?> expression() {
    return selectable.expression();
  }

  OrderByItem ast() {
    return new OrderByItem(
        expression(),
        direction == SortDirection.ASC ? OrderDirection.ASC : OrderDirection.DESC,
        switch (nullPlacement) {
          case DIALECT_DEFAULT -> NullOrder.DIALECT_DEFAULT;
          case FIRST -> NullOrder.FIRST;
          case LAST -> NullOrder.LAST;
        });
  }

  boolean sameOccurrence(SortSpecification other) {
    Objects.requireNonNull(other, "other");
    return SelectableSupport.sameOccurrence(selectable, other.selectable)
        && direction == other.direction
        && nullPlacement == other.nullPlacement;
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof SortSpecification specification
            && expression().equals(specification.expression())
            && direction == specification.direction
            && nullPlacement == specification.nullPlacement;
  }

  @Override
  public int hashCode() {
    return Objects.hash(expression(), direction, nullPlacement);
  }

  @Override
  public String toString() {
    return "SortSpecification[expression="
        + SelectableSupport.summary(selectable)
        + ", direction="
        + direction
        + ", nullPlacement="
        + nullPlacement
        + ']';
  }

  private SortSpecification withNullPlacement(NullPlacement placement) {
    return placement == nullPlacement
        ? this
        : new SortSpecification(selectable, direction, placement);
  }
}
