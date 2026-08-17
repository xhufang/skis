package io.skis.metadata;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable primary-key metadata.
 *
 * @param <E> declaring entity type
 */
public record PrimaryKeyMeta<E>(List<PropertyMeta<E, ?>> properties) {

  /** Validates and defensively copies the ordered primary-key properties. */
  public PrimaryKeyMeta {
    Objects.requireNonNull(properties, "properties");
    properties = List.copyOf(properties);
    if (properties.isEmpty()) {
      throw new IllegalArgumentException("a primary key must contain at least one property");
    }

    Set<String> names = new HashSet<>();
    for (PropertyMeta<E, ?> property : properties) {
      Objects.requireNonNull(property, "primary-key property");
      if (!names.add(property.name())) {
        throw new IllegalArgumentException(
            "duplicate primary-key property '" + property.name() + "'");
      }
      if (property.column().nullable()) {
        throw new IllegalArgumentException(
            "primary-key property '" + property.name() + "' must not be nullable");
      }
    }
  }

  /** Returns whether this is a composite primary key. */
  public boolean composite() {
    return properties.size() > 1;
  }
}
