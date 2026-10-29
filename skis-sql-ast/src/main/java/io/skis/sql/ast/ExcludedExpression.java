package io.skis.sql.ast;

import java.util.Objects;

/** Proposed INSERT column, visible only inside its owning ON CONFLICT update. */
public record ExcludedExpression<V>(ColumnExpression<?, V> column) implements SqlExpression<V> {

  public ExcludedExpression {
    Objects.requireNonNull(column, "column");
  }

  @Override
  public Class<V> javaType() {
    return column.javaType();
  }

  @Override
  public SqlType sqlType() {
    return column.sqlType();
  }

  @Override
  public Nullability nullability() {
    return column.nullability();
  }

  @Override
  public boolean nullable() {
    return column.nullable();
  }
}
