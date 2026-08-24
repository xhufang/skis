package io.skis.mapping;

import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import java.util.List;
import java.util.Objects;

/**
 * Immutable bridge from structural entity metadata to generated JDBC behavior.
 *
 * <p>The model is created by generated code. It keeps reflection and annotation scanning out of
 * query compilation, parameter binding, and row decoding.
 */
public final class EntityRuntimeModel<E> {

  private final EntityMeta<E> entity;
  private final RowDecoderFactory<E> rowDecoderFactory;
  private final RowDecoder<E> fullRowDecoder;
  private final List<PropertyRuntime<E, ?>> properties;

  /** Creates and validates a generated entity runtime model. */
  public EntityRuntimeModel(
      EntityMeta<E> entity,
      RowDecoderFactory<E> rowDecoderFactory,
      List<? extends PropertyRuntime<E, ?>> properties) {
    this.entity = Objects.requireNonNull(entity, "entity");
    this.rowDecoderFactory = Objects.requireNonNull(rowDecoderFactory, "rowDecoderFactory");
    this.properties = List.copyOf(Objects.requireNonNull(properties, "properties"));
    if (this.properties.size() != entity.properties().size()) {
      throw new IllegalArgumentException(
          "runtime property count for entity '"
              + entity.entityName()
              + "' does not match its metadata");
    }
    for (int ordinal = 0; ordinal < this.properties.size(); ordinal++) {
      PropertyRuntime<E, ?> runtime =
          Objects.requireNonNull(this.properties.get(ordinal), "property runtime");
      if (runtime.property() != entity.properties().get(ordinal)) {
        throw new IllegalArgumentException(
            "runtime property at ordinal "
                + ordinal
                + " does not use the canonical metadata of entity '"
                + entity.entityName()
                + "'");
      }
    }
    this.fullRowDecoder =
        Objects.requireNonNull(
            rowDecoderFactory.create(RowLayout.contiguous(this.properties.size(), 1)),
            "full row decoder");
  }

  public EntityMeta<E> entity() {
    return entity;
  }

  public List<PropertyRuntime<E, ?>> properties() {
    return properties;
  }

  /** Creates a generated decoder for an explicit result-set layout. */
  public RowDecoder<E> rowDecoder(RowLayout layout) {
    return Objects.requireNonNull(
        rowDecoderFactory.create(Objects.requireNonNull(layout, "layout")), "row decoder");
  }

  /** Returns the shared generated decoder for every persistent property in ordinal order. */
  public RowDecoder<E> fullRowDecoder() {
    return fullRowDecoder;
  }

  /** Returns the generated JDBC behavior for a canonical entity property. */
  @SuppressWarnings("unchecked")
  public <V> PropertyRuntime<E, V> property(PropertyMeta<E, V> property) {
    Objects.requireNonNull(property, "property");
    int ordinal = property.ordinal();
    if (ordinal < 0
        || ordinal >= properties.size()
        || properties.get(ordinal).property() != property) {
      throw new IllegalArgumentException(
          "property '"
              + property.name()
              + "' does not belong to runtime model '"
              + entity.entityName()
              + "'");
    }
    return (PropertyRuntime<E, V>) properties.get(ordinal);
  }
}
