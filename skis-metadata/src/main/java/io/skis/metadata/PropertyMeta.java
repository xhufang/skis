package io.skis.metadata;

import java.util.Objects;

/**
 * Immutable metadata for a persistent scalar property.
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
    Objects.requireNonNull(javaType, "javaType");
    Objects.requireNonNull(column, "column");
    if (name.isBlank()) {
      throw new IllegalArgumentException("property name must not be blank");
    }
  }
}
