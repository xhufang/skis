package io.skis.sql.ast;

import java.util.Objects;

/** Immutable portable SQL {@code CAST} expression. */
public record CastExpression<T>(SqlExpression<?> operand, Class<T> javaType, SqlType sqlType)
    implements SqlExpression<T> {

  /** Creates a cast whose target SQL type is inferred from its Java representation. */
  public CastExpression(SqlExpression<?> operand, Class<T> javaType) {
    this(operand, javaType, SqlType.fromJavaType(javaType));
  }

  /** Creates a cast after validating its source and target descriptors. */
  public CastExpression {
    Objects.requireNonNull(operand, "operand");
    Objects.requireNonNull(javaType, "javaType");
    Objects.requireNonNull(sqlType, "sqlType");
    javaType = SemanticValidator.boxedJavaType(javaType);
    SemanticValidator.validateCast(operand, javaType, sqlType);
  }

  @Override
  public Nullability nullability() {
    return operand.nullability();
  }

  @Override
  public boolean nullable() {
    return operand.nullable();
  }
}
