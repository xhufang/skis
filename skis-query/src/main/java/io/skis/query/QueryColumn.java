package io.skis.query;

import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlType;
import java.util.Objects;

/** Package-owned physical-column selectable backed by generated entity metadata. */
abstract sealed class QueryColumn<E, V> implements Selectable<V>
    permits NonNullQueryColumn, NullableQueryColumn {

  private final QueryTable<E> table;
  private final ColumnExpression<E, V> expression;

  QueryColumn(QueryTable<E> table, ColumnExpression<E, V> expression) {
    this.table = Objects.requireNonNull(table, "table");
    this.expression = Objects.requireNonNull(expression, "expression");
    if (expression.table() != table.tableExpression()) {
      throw new IllegalArgumentException("query column expression belongs to another table");
    }
  }

  final PropertyMeta<E, V> property() {
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

  final boolean nullable() {
    return expression.nullable();
  }

  final ColumnExpression<E, V> expression() {
    return expression;
  }

  final QueryTable<E> table() {
    return table;
  }
}

/** Package-owned generated column with a declared non-null SQL value. */
final class NonNullQueryColumn<E, V> extends QueryColumn<E, V> implements NonNullSelectable<V> {

  NonNullQueryColumn(QueryTable<E> table, ColumnExpression<E, V> expression) {
    super(table, expression);
    if (expression.nullable()) {
      throw new IllegalArgumentException("non-null query column uses nullable metadata");
    }
  }
}

/** Package-owned generated column whose SQL value may be null. */
final class NullableQueryColumn<E, V> extends QueryColumn<E, V> {

  NullableQueryColumn(QueryTable<E> table, ColumnExpression<E, V> expression) {
    super(table, expression);
    if (!expression.nullable()) {
      throw new IllegalArgumentException("nullable query column uses non-null metadata");
    }
  }
}
