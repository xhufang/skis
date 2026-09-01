package io.skis.sql.ast;

import java.util.Objects;

/**
 * Structural placeholder for a bound JDBC parameter.
 *
 * <p>A slot deliberately contains no runtime value, so AST equality and hash codes are stable
 * across executions with different input values.
 */
public record ParameterSlot<T>(
    int ordinal, Class<T> javaType, SqlType sqlType, Nullability nullability)
    implements SqlExpression<T> {

  /** Creates a slot using the SQL type inferred from its Java representation. */
  public ParameterSlot(int ordinal, Class<T> javaType, boolean nullable) {
    this(ordinal, javaType, SqlType.fromJavaType(javaType), Nullability.of(nullable));
  }

  /** Creates a slot using the SQL type inferred from its Java representation. */
  public ParameterSlot(int ordinal, Class<T> javaType, Nullability nullability) {
    this(ordinal, javaType, SqlType.fromJavaType(javaType), nullability);
  }

  /** Validates and creates a parameter slot. */
  public ParameterSlot {
    if (ordinal < 0) {
      throw new IllegalArgumentException("parameter ordinal must not be negative");
    }
    Objects.requireNonNull(javaType, "javaType");
    Objects.requireNonNull(sqlType, "sqlType");
    Objects.requireNonNull(nullability, "nullability");
    if (javaType == void.class || javaType == Void.class) {
      throw new IllegalArgumentException("parameter Java type must not be void");
    }
    if (nullability.isNullable() && javaType.isPrimitive()) {
      throw new IllegalArgumentException("a primitive parameter Java type cannot be nullable");
    }
    javaType = boxed(javaType);
  }

  /** Whether the bound value may be {@code null}. */
  @Override
  public boolean nullable() {
    return nullability.isNullable();
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> boxed(Class<T> javaType) {
    if (!javaType.isPrimitive()) {
      return javaType;
    }
    Class<?> boxedType =
        switch (javaType.getName()) {
          case "boolean" -> Boolean.class;
          case "byte" -> Byte.class;
          case "short" -> Short.class;
          case "int" -> Integer.class;
          case "long" -> Long.class;
          case "float" -> Float.class;
          case "double" -> Double.class;
          case "char" -> Character.class;
          default ->
              throw new IllegalArgumentException(
                  "unsupported primitive parameter Java type " + javaType.getTypeName());
        };
    return (Class<T>) boxedType;
  }
}
