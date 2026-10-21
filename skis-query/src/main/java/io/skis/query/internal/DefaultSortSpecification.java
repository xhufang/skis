package io.skis.query.internal;

import io.skis.query.NullPlacement;
import io.skis.query.Selectable;
import io.skis.query.SortDirection;
import io.skis.query.SortSpecification;
import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.OrderDirection;
import io.skis.sql.ast.SqlExpression;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable ordering item over one framework-owned selectable expression. */
record DefaultSortSpecification(
    Selectable<?> selectable, SortDirection direction, NullPlacement nullPlacement)
    implements SortSpecification {

  DefaultSortSpecification(
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

  SqlExpression<?> expression() {
    return SelectableSupport.expression(selectable);
  }

  OrderByItem ast() {
    return ast(expression());
  }

  OrderByItem ast(SqlExpression<?> compiledExpression) {
    return new OrderByItem(
        Objects.requireNonNull(compiledExpression, "compiledExpression"),
        direction == SortDirection.ASC ? OrderDirection.ASC : OrderDirection.DESC,
        switch (nullPlacement) {
          case DIALECT_DEFAULT -> NullOrder.DIALECT_DEFAULT;
          case FIRST -> NullOrder.FIRST;
          case LAST -> NullOrder.LAST;
        });
  }

  boolean sameOccurrence(SortSpecification other) {
    Objects.requireNonNull(other, "other");
    return SelectableSupport.sameOccurrence(selectable, QueryModels.selectable(other))
        && direction == other.direction()
        && nullPlacement == other.nullPlacement();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    return this == other
        || other
                instanceof
                DefaultSortSpecification(
                    Selectable<?> selectable1,
                    SortDirection direction1,
                    NullPlacement placement)
            && SelectableSupport.expressionIdentity(selectable)
                .equals(SelectableSupport.expressionIdentity(selectable1))
            && direction == direction1
            && nullPlacement == placement;
  }

  @Override
  public int hashCode() {
    return Objects.hash(SelectableSupport.expressionIdentity(selectable), direction, nullPlacement);
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
        : new DefaultSortSpecification(selectable, direction, placement);
  }
}
