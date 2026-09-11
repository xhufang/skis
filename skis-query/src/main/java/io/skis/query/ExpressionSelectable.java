package io.skis.query;

import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import java.util.Objects;

/** Package-owned wrapper used by later standard expression factories. */
sealed class ExpressionSelectable<V> implements Selectable<V>
    permits NonNullExpressionSelectable {

  private final SqlExpression<V> expression;

  ExpressionSelectable(SqlExpression<V> expression) {
    this.expression = Objects.requireNonNull(expression, "expression");
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

  @Override
  public final SqlExpression<V> expression() {
    return expression;
  }
}

/** Package-owned wrapper whose AST has a declared non-null result. */
final class NonNullExpressionSelectable<V> extends ExpressionSelectable<V>
    implements NonNullSelectable<V> {

  NonNullExpressionSelectable(SqlExpression<V> expression) {
    super(expression);
    if (expression.nullability().isNullable()) {
      throw new IllegalArgumentException("non-null selectable uses a nullable SQL expression");
    }
  }
}
