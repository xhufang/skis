package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.sql.ast.Nullability;
import java.util.Objects;

/**
 * Opaque, strongly typed reference to one query value.
 *
 * <p>A reference is identified by object identity. It deliberately contains neither a runtime value
 * nor a query-block, logical-slot, or JDBC parameter ordinal.
 */
final class DefaultQueryParameter<V> implements QueryParameter<V> {

  private final Class<V> javaType;
  private final Nullability nullability;

  DefaultQueryParameter(Class<V> javaType, Nullability nullability) {
    this.javaType = boxed(Objects.requireNonNull(javaType, "javaType"));
    this.nullability = Objects.requireNonNull(nullability, "nullability");
    if (this.javaType == Void.class) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_TYPE_MISMATCH, "query parameter Java type must not be void");
    }
  }

  /** Returns the boxed Java type accepted by this reference. */
  @Override
  public Class<V> javaType() {
    return javaType;
  }

  @Override
  public Nullability nullability() {
    return nullability;
  }

  static <V> QueryParameter<V> anonymousNonNull(Class<V> javaType) {
    return new DefaultQueryParameter<>(javaType, Nullability.NON_NULL);
  }

  @Override
  public String toString() {
    return "QueryParameter<" + javaType.getTypeName() + ">";
  }

  @SuppressWarnings("unchecked")
  private static <V> Class<V> boxed(Class<V> javaType) {
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
              throw QueryValidationException.of(
                  SkisErrorCode.QUERY_TYPE_MISMATCH,
                  "unsupported primitive query parameter Java type " + javaType.getTypeName());
        };
    return (Class<V>) boxedType;
  }
}
