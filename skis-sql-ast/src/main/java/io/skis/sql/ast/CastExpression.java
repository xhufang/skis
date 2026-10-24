package io.skis.sql.ast;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable portable SQL {@code CAST} expression. */
public record CastExpression<T>(
    SqlExpression<?> operand,
    Class<T> javaType,
    SqlType sqlType,
    @Nullable DecimalPrecision decimalPrecision)
    implements SqlExpression<T> {

  /** Creates a cast whose target SQL type is inferred from its Java representation. */
  public CastExpression(SqlExpression<?> operand, Class<T> javaType) {
    this(operand, javaType, SqlType.fromJavaType(javaType));
  }

  /** Creates a CAST without imposing a finite decimal precision or scale. */
  public CastExpression(SqlExpression<?> operand, Class<T> javaType, SqlType sqlType) {
    this(operand, javaType, sqlType, null);
  }

  /** Creates a cast after validating its source and target descriptors. */
  public CastExpression {
    Objects.requireNonNull(operand, "operand");
    Objects.requireNonNull(javaType, "javaType");
    Objects.requireNonNull(sqlType, "sqlType");
    javaType = SemanticValidator.boxedJavaType(javaType);
    SemanticValidator.validateCast(operand, javaType, sqlType);
    if (decimalPrecision != null && sqlType != SqlType.DECIMAL) {
      throw new IllegalArgumentException("precision/scale is only valid for a DECIMAL CAST target");
    }
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
