package io.skis.metadata;

import io.skis.core.SkisConfigurationException;
import io.skis.core.SkisErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable primary-key metadata.
 *
 * @param <E> declaring entity type
 */
public record PrimaryKeyMeta<E>(List<PropertyMeta<E, ?>> properties, IdGeneration generation) {

  /** Creates an application-assigned primary key. */
  public PrimaryKeyMeta(List<PropertyMeta<E, ?>> properties) {
    this(properties, IdGeneration.ASSIGNED);
  }

  /** Validates and defensively copies the ordered primary-key properties. */
  public PrimaryKeyMeta {
    Objects.requireNonNull(properties, "properties");
    properties = List.copyOf(properties);
    Objects.requireNonNull(generation, "generation");
    if (properties.isEmpty()) {
      throw new IllegalArgumentException("a primary key must contain at least one property");
    }
    if (generation != IdGeneration.ASSIGNED && properties.size() != 1) {
      throw invalid("generated primary keys require exactly one property");
    }
    PropertyMeta<E, ?> firstProperty = properties.getFirst();
    Class<?> type = firstProperty.javaType();
    boolean valid =
        switch (generation) {
          case IDENTITY -> type == Long.class || type == Integer.class;
          case UUID -> type == java.util.UUID.class || type == String.class;
          case SNOWFLAKE -> type == Long.class;
          case ASSIGNED -> true;
        };
    if (!valid) {
      throw invalid("primary-key generation strategy does not match the property type");
    }
    if (generation != IdGeneration.ASSIGNED) {
      if (generation != IdGeneration.IDENTITY && !firstProperty.column().insertable()) {
        throw invalid("application-generated primary keys must be insertable");
      }
      if (generation == IdGeneration.UUID
          && type == String.class
          && firstProperty.column().length() > 0
          && firstProperty.column().length() < 36) {
        throw invalid("UUID text primary-key column must hold at least 36 characters");
      }
    } else if (properties.stream().anyMatch(property -> !property.column().insertable())) {
      throw invalid("assigned primary keys must be insertable");
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

  private static SkisConfigurationException invalid(String detail) {
    return SkisConfigurationException.of(SkisErrorCode.METADATA_INVALID, detail);
  }
}
