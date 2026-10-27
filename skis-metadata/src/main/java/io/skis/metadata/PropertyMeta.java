package io.skis.metadata;

import java.util.Objects;

/**
 * Immutable metadata for a persistent scalar property.
 *
 * <p>Primitive value types are normalized to their wrappers once at construction, matching generated
 * metadata and the boxed values passed through runtime mapping contracts.
 *
 * @param <E> declaring entity type
 * @param <V> property value type
 */
public record PropertyMeta<E, V>(int ordinal, String name, Class<V> javaType, ColumnMeta column) {

  /** Validates and creates property metadata. */
  public PropertyMeta {
    if (ordinal < 0) {
      throw new IllegalArgumentException("property ordinal must not be negative");
    }
    Objects.requireNonNull(name, "name");
    javaType = boxed(Objects.requireNonNull(javaType, "javaType"));
    Objects.requireNonNull(column, "column");
    if (name.isBlank()) {
      throw new IllegalArgumentException("property name must not be blank");
    }
  }

  @SuppressWarnings("unchecked")
  private static <V> Class<V> boxed(Class<V> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    Class<?> valueType =
        switch (type.getName()) {
          case "boolean" -> Boolean.class;
          case "byte" -> Byte.class;
          case "short" -> Short.class;
          case "int" -> Integer.class;
          case "long" -> Long.class;
          case "float" -> Float.class;
          case "double" -> Double.class;
          case "char" -> Character.class;
          default -> type;
        };
    return (Class<V>) valueType;
  }
}
