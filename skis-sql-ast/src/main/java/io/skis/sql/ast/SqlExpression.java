package io.skis.sql.ast;

/**
 * A typed, immutable SQL value expression.
 *
 * @param <T> Java representation of the SQL value
 */
public interface SqlExpression<T> {

  /** Java type produced by the expression. */
  Class<T> javaType();

  /** Portable SQL type used for semantic validation and dialect lowering. */
  SqlType sqlType();

  /** Explicit SQL nullability propagated through expression nodes. */
  Nullability nullability();

  /** Whether SQL evaluation may produce {@code NULL}. */
  boolean nullable();
}
