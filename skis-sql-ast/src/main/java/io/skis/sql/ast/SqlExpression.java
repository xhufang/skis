package io.skis.sql.ast;

/**
 * A typed, immutable SQL value expression.
 *
 * <p>This low-level interface is public for AST interoperability, not as an unchecked expression
 * extension SPI. Semantic analysis and rendering fail closed for implementations the framework
 * does not explicitly support.
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
