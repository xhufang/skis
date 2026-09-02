package io.skis.query;

import io.skis.sql.ast.ColumnExpression;

/** Generated column whose SQL value is guaranteed to be non-null. */
public final class NonNullQueryColumn<E, V> extends QueryColumn<E, V> {

  NonNullQueryColumn(ColumnExpression<E, V> expression) {
    super(expression);
    if (expression.nullable()) {
      throw new IllegalArgumentException("non-null query column uses nullable metadata");
    }
  }
}
