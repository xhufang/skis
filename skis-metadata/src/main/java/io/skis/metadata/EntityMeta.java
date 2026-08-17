package io.skis.metadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, thread-safe description of an entity mapping.
 *
 * <p>This type contains structural metadata only. It deliberately has no reflection accessors, JDBC
 * behavior, SQL rendering, or database-dialect dependencies.
 *
 * @param <E> entity type
 */
public final class EntityMeta<E> {

  private final Class<E> javaType;
  private final String entityName;
  private final EntityMode mode;
  private final TableMeta table;
  private final List<PropertyMeta<E, ?>> properties;
  private final Map<String, PropertyMeta<E, ?>> propertiesByName;
  private final @Nullable PrimaryKeyMeta<E> primaryKey;
  private final boolean readOnly;

  /**
   * Creates validated entity metadata.
   *
   * @param javaType entity Java type
   * @param entityName logical entity name used in diagnostics
   * @param mode entity representation mode
   * @param table physical table identity
   * @param properties ordered persistent properties; each ordinal must match its list index
   * @param primaryKey primary key, or {@code null} for a read-only entity without one
   * @param readOnly whether mutation APIs must reject the entity
   */
  public EntityMeta(
      Class<E> javaType,
      String entityName,
      EntityMode mode,
      TableMeta table,
      List<PropertyMeta<E, ?>> properties,
      @Nullable PrimaryKeyMeta<E> primaryKey,
      boolean readOnly) {
    this.javaType = Objects.requireNonNull(javaType, "javaType");
    this.entityName = requireEntityName(entityName);
    this.mode = Objects.requireNonNull(mode, "mode");
    this.table = Objects.requireNonNull(table, "table");
    this.properties = copyAndValidateProperties(properties);
    this.propertiesByName = indexProperties(this.properties);
    validateColumnMappings(this.properties);
    this.primaryKey = primaryKey;
    this.readOnly = readOnly;

    validatePrimaryKey(primaryKey);
    if (!readOnly && primaryKey == null) {
      throw new IllegalArgumentException(
          "writable entity '" + entityName + "' requires a primary key");
    }
  }

  /** Creates metadata for a simple class or record entity. */
  public static <E> EntityMeta<E> simple(
      Class<E> javaType,
      TableMeta table,
      List<PropertyMeta<E, ?>> properties,
      @Nullable PrimaryKeyMeta<E> primaryKey,
      boolean readOnly) {
    return new EntityMeta<>(
        javaType,
        javaType.getSimpleName(),
        EntityMode.SIMPLE,
        table,
        properties,
        primaryKey,
        readOnly);
  }

  public Class<E> javaType() {
    return javaType;
  }

  public String entityName() {
    return entityName;
  }

  public EntityMode mode() {
    return mode;
  }

  public TableMeta table() {
    return table;
  }

  public List<PropertyMeta<E, ?>> properties() {
    return properties;
  }

  public Optional<PrimaryKeyMeta<E>> primaryKey() {
    return Optional.ofNullable(primaryKey);
  }

  public boolean readOnly() {
    return readOnly;
  }

  /** Looks up a persistent property by its Java name. */
  public Optional<PropertyMeta<E, ?>> findProperty(String propertyName) {
    Objects.requireNonNull(propertyName, "propertyName");
    return Optional.ofNullable(propertiesByName.get(propertyName));
  }

  /**
   * Returns a persistent property by its Java name.
   *
   * @throws IllegalArgumentException when the property is not part of this entity
   */
  public PropertyMeta<E, ?> property(String propertyName) {
    return findProperty(propertyName)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "unknown property '" + propertyName + "' on entity '" + entityName + "'"));
  }

  private List<PropertyMeta<E, ?>> copyAndValidateProperties(
      List<PropertyMeta<E, ?>> candidateProperties) {
    Objects.requireNonNull(candidateProperties, "properties");
    List<PropertyMeta<E, ?>> result = List.copyOf(candidateProperties);
    if (result.isEmpty()) {
      throw new IllegalArgumentException("entity '" + entityName + "' must contain a property");
    }
    for (int index = 0; index < result.size(); index++) {
      PropertyMeta<E, ?> property = Objects.requireNonNull(result.get(index), "property");
      if (property.ordinal() != index) {
        throw new IllegalArgumentException(
            "property '"
                + property.name()
                + "' has ordinal "
                + property.ordinal()
                + " but expected "
                + index);
      }
    }
    return result;
  }

  private Map<String, PropertyMeta<E, ?>> indexProperties(
      List<PropertyMeta<E, ?>> candidateProperties) {
    Map<String, PropertyMeta<E, ?>> result = new LinkedHashMap<>();
    for (PropertyMeta<E, ?> property : candidateProperties) {
      PropertyMeta<E, ?> previous = result.putIfAbsent(property.name(), property);
      if (previous != null) {
        throw new IllegalArgumentException(
            "duplicate property '" + property.name() + "' on entity '" + entityName + "'");
      }
    }
    return Map.copyOf(result);
  }

  private void validateColumnMappings(List<PropertyMeta<E, ?>> candidateProperties) {
    Map<String, PropertyMeta<E, ?>> writablePropertiesByColumn = new LinkedHashMap<>();
    for (PropertyMeta<E, ?> property : candidateProperties) {
      ColumnMeta column = property.column();
      if (!column.insertable() && !column.updatable()) {
        continue;
      }
      PropertyMeta<E, ?> previous = writablePropertiesByColumn.putIfAbsent(column.name(), property);
      if (previous != null) {
        throw new IllegalArgumentException(
            "writable properties '"
                + previous.name()
                + "' and '"
                + property.name()
                + "' map to the same column '"
                + column.name()
                + "' on entity '"
                + entityName
                + "'");
      }
    }
  }

  private void validatePrimaryKey(@Nullable PrimaryKeyMeta<E> candidatePrimaryKey) {
    if (candidatePrimaryKey == null) {
      return;
    }
    for (PropertyMeta<E, ?> property : candidatePrimaryKey.properties()) {
      PropertyMeta<E, ?> entityProperty = propertiesByName.get(property.name());
      if (entityProperty == null || !entityProperty.equals(property)) {
        throw new IllegalArgumentException(
            "primary-key property '"
                + property.name()
                + "' does not belong to entity '"
                + entityName
                + "'");
      }
    }
  }

  private static String requireEntityName(String entityName) {
    Objects.requireNonNull(entityName, "entityName");
    if (entityName.isBlank()) {
      throw new IllegalArgumentException("entityName must not be blank");
    }
    return entityName;
  }
}
