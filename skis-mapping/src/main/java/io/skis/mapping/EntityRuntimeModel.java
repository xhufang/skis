package io.skis.mapping;

import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
  private final @Nullable EntityMutationBinders<E> mutationBinders;

  /** Creates and validates a generated entity runtime model. */
  public EntityRuntimeModel(
      EntityMeta<E> entity,
      RowDecoderFactory<E> rowDecoderFactory,
      List<? extends PropertyRuntime<E, ?>> properties) {
    this(entity, rowDecoderFactory, properties, null);
  }

  /** Creates and validates a generated entity runtime model with mutation Fast Paths. */
  public EntityRuntimeModel(
      EntityMeta<E> entity,
      RowDecoderFactory<E> rowDecoderFactory,
      List<? extends PropertyRuntime<E, ?>> properties,
      @Nullable EntityMutationBinders<E> mutationBinders) {
    this.entity = Objects.requireNonNull(entity, "entity");
    this.rowDecoderFactory = Objects.requireNonNull(rowDecoderFactory, "rowDecoderFactory");
    this.properties = List.copyOf(Objects.requireNonNull(properties, "properties"));
    this.mutationBinders = mutationBinders;
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
    if (entity.readOnly() && mutationBinders != null) {
      throw new IllegalArgumentException(
          "read-only entity '" + entity.entityName() + "' must not expose mutation binders");
    }
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

  /**
   * Creates a decoder for an entity selected from a null-extended outer-join occurrence.
   *
   * <p>All primary-key columns being {@code NULL} means that the joined entity is absent. A fully
   * present key delegates to the generated entity decoder, while a partially {@code NULL} composite
   * key is reported as a mapping-contract failure before entity construction.
   */
  public RowDecoder<E> nullableRowDecoder(RowLayout layout) {
    Objects.requireNonNull(layout, "layout");
    PrimaryKeyMeta<E> primaryKey =
        entity
            .primaryKey()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "nullable entity decoding requires primary-key metadata for entity '"
                            + entity.entityName()
                            + "'"));
    RowDecoder<E> entityDecoder = rowDecoder(layout);
    int[] keyIndexes = new int[primaryKey.properties().size()];
    JdbcTypeCodec<?>[] keyCodecs = new JdbcTypeCodec<?>[primaryKey.properties().size()];
    for (int index = 0; index < primaryKey.properties().size(); index++) {
      PropertyMeta<E, ?> property = primaryKey.properties().get(index);
      keyIndexes[index] = layout.requireIndex(property.ordinal());
      keyCodecs[index] = properties.get(property.ordinal()).codec();
    }
    return (resultSet, context) -> {
      int nullKeys = 0;
      for (int index = 0; index < keyIndexes.length; index++) {
        if (keyCodecs[index].read(resultSet, keyIndexes[index], context) == null) {
          nullKeys++;
        }
      }
      if (nullKeys == keyIndexes.length) {
        return null;
      }
      if (nullKeys != 0) {
        throw new SQLException(
            "nullable entity '"
                + entity.entityName()
                + "' has a partially null primary key in the result row");
      }
      E decoded = entityDecoder.decode(resultSet, context);
      if (decoded == null) {
        throw new SQLException(
            "generated decoder returned null for present entity '" + entity.entityName() + "'");
      }
      return decoded;
    };
  }

  /** Returns the shared generated decoder for every persistent property in ordinal order. */
  public RowDecoder<E> fullRowDecoder() {
    return fullRowDecoder;
  }

  /** Returns generated mutation bindings when the entity was compiled for writes. */
  public Optional<EntityMutationBinders<E>> mutationBinders() {
    return Optional.ofNullable(mutationBinders);
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
