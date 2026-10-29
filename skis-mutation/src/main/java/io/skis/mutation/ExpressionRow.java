package io.skis.mutation;

import io.skis.query.NonNullSelectable;
import io.skis.query.Selectable;

/** Callback-scoped collector of typed columns and SQL expressions. */
public interface ExpressionRow {
  <V> ExpressionRow value(NonNullSelectable<V> column, NonNullSelectable<V> expression);

  <V> ExpressionRow value(Selectable<V> column, Selectable<V> expression);
}
