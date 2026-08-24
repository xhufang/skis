package io.skis.metadata;

import java.util.Objects;

/**
 * Immutable optimistic-version metadata.
 *
 * @param <E> declaring entity type
 * @param <V> version value type
 */
public record VersionMeta<E, V>(PropertyMeta<E, V> property, VersionStrategy strategy) {

  /** Validates and creates version metadata. */
  public VersionMeta {
    Objects.requireNonNull(property, "property");
    Objects.requireNonNull(strategy, "strategy");

    if (strategy.unSupportsJavaType(property.javaType())) {
      throw new IllegalArgumentException(
          "version strategy '"
              + strategy
              + "' does not support Java type '"
              + property.javaType().getName()
              + "'");
    }
    if (property.column().nullable()) {
      throw new IllegalArgumentException(
          "version property '" + property.name() + "' must not be nullable");
    }
    if (strategy.requiresInsertableColumn() && !property.column().insertable()) {
      throw new IllegalArgumentException(
          "version strategy '"
              + strategy
              + "' requires property '"
              + property.name()
              + "' to be insertable");
    }
    if (strategy.requiresUpdatableColumn() && !property.column().updatable()) {
      throw new IllegalArgumentException(
          "version strategy '"
              + strategy
              + "' requires property '"
              + property.name()
              + "' to be updatable");
    }
  }
}
