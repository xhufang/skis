package io.skis.query;

import io.skis.sql.ast.ColumnExpression;

/** Generated column whose SQL value may be null. */
public final class NullableQueryColumn<E, V> extends QueryColumn<E, V> {

  NullableQueryColumn(ColumnExpression<E, V> expression) {
    super(expression);
    if (!expression.nullable()) {
      throw new IllegalArgumentException("nullable query column uses non-null metadata");
    }
  }
}
