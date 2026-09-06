package io.skis.query;

import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;

/**
 * Framework-owned read-only SQL selection expression.
 *
 * <p>The sealed contract prevents application implementations from injecting arbitrary SQL. In
 * 0.2.4 generated query columns are its only concrete values; later standard expression types can
 * join the same result-shape contract without changing generated projection APIs.
 */
public sealed interface Selectable<V> permits NonNullSelectable, QueryColumn {

  /** Returns the boxed Java value type produced by this expression. */
  Class<V> javaType();

  /** Returns the portable SQL type produced by this expression. */
  SqlType sqlType();

  /** Returns the expression's declared nullability before query-local Join propagation. */
  Nullability nullability();

  /** Returns the immutable SQL AST represented by this selection. */
  SqlExpression<V> expression();
}
