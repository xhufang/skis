package io.skis.query;

import io.skis.sql.ast.Nullability;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Opaque, strongly typed reference to one query value.
 *
 * <p>A reference is identified by object identity. It deliberately contains neither a runtime
 * value nor a query-block, logical-slot, or JDBC parameter ordinal. An optional name is diagnostic
 * information only and never participates in parameter identity.
 */
public final class QueryParameter<V> {

  private final Class<V> javaType;
  private final Nullability nullability;
  private final @Nullable String diagnosticName;

  QueryParameter(
      Class<V> javaType, Nullability nullability, @Nullable String diagnosticName) {
    this.javaType = boxed(Objects.requireNonNull(javaType, "javaType"));
    this.nullability = Objects.requireNonNull(nullability, "nullability");
    if (this.javaType == Void.class) {
      throw new IllegalArgumentException("query parameter Java type must not be void");
    }
    if (diagnosticName != null && diagnosticName.isBlank()) {
      throw new IllegalArgumentException("query parameter diagnostic name must not be blank");
    }
    this.diagnosticName = diagnosticName;
  }

  /** Returns the boxed Java type accepted by this reference. */
  public Class<V> javaType() {
    return javaType;
  }

  /** Returns the optional diagnostic name; the name is not the parameter's identity. */
  public Optional<String> diagnosticName() {
    return Optional.ofNullable(diagnosticName);
  }

  Nullability nullability() {
    return nullability;
  }

  static <V> QueryParameter<V> anonymousNonNull(Class<V> javaType) {
    return new QueryParameter<>(javaType, Nullability.NON_NULL, null);
  }

  @Override
  public String toString() {
    return diagnosticName == null
        ? "QueryParameter<" + javaType.getTypeName() + ">"
        : "QueryParameter[" + diagnosticName + ": " + javaType.getTypeName() + "]";
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
              throw new IllegalArgumentException(
                  "unsupported primitive query parameter Java type " + javaType.getTypeName());
        };
    return (Class<V>) boxedType;
  }
}
