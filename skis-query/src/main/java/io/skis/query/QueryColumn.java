package io.skis.query;

import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlType;
import java.util.Objects;

/** Physical-column selectable backed by generated entity metadata. */
public abstract sealed class QueryColumn<E, V> implements Selectable<V>
    permits NonNullQueryColumn, NullableQueryColumn {

  private final ColumnExpression<E, V> expression;

  QueryColumn(ColumnExpression<E, V> expression) {
    this.expression = Objects.requireNonNull(expression, "expression");
  }

  public final PropertyMeta<E, V> property() {
    return expression.property();
  }

  @Override
  public final Class<V> javaType() {
    return expression.javaType();
  }

  @Override
  public final SqlType sqlType() {
    return expression.sqlType();
  }

  @Override
  public final Nullability nullability() {
    return expression.nullability();
  }

  public final boolean nullable() {
    return expression.nullable();
  }

  @Override
  public final ColumnExpression<E, V> expression() {
    return expression;
  }

  final QueryTable<E> table() {
    return (QueryTable<E>) expression.table();
  }
}
