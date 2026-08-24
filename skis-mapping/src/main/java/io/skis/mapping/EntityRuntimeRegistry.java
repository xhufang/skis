package io.skis.mapping;

import io.skis.metadata.EntityMeta;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, identity-keyed registry of generated entity runtime models. */
public final class EntityRuntimeRegistry {

  private final Map<EntityMeta<?>, EntityRuntimeModel<?>> models;

  private EntityRuntimeRegistry(Map<EntityMeta<?>, EntityRuntimeModel<?>> models) {
    this.models = Collections.unmodifiableMap(models);
  }

  /** Creates a registry and rejects duplicate canonical entity metadata. */
  public static EntityRuntimeRegistry of(Collection<? extends EntityRuntimeModel<?>> models) {
    Objects.requireNonNull(models, "models");
    Map<EntityMeta<?>, EntityRuntimeModel<?>> indexed = new IdentityHashMap<>();
    Map<Class<?>, EntityMeta<?>> entitiesByJavaType = new IdentityHashMap<>();
    for (EntityRuntimeModel<?> model : models) {
      Objects.requireNonNull(model, "entity runtime model");
      EntityRuntimeModel<?> previous = indexed.put(model.entity(), model);
      if (previous != null) {
        throw new IllegalArgumentException(
            "duplicate runtime model for entity '" + model.entity().entityName() + "'");
      }
      EntityMeta<?> previousEntity =
          entitiesByJavaType.put(model.entity().javaType(), model.entity());
      if (previousEntity != null && previousEntity != model.entity()) {
        throw new IllegalArgumentException(
            "multiple canonical runtime models use Java type "
                + model.entity().javaType().getTypeName());
      }
    }
    return new EntityRuntimeRegistry(indexed);
  }

  /** Returns an empty registry. */
  public static EntityRuntimeRegistry empty() {
    return new EntityRuntimeRegistry(new IdentityHashMap<>());
  }

  /** Looks up an entity by canonical metadata identity. */
  @SuppressWarnings("unchecked")
  public <E> Optional<EntityRuntimeModel<E>> find(EntityMeta<E> entity) {
    Objects.requireNonNull(entity, "entity");
    return Optional.ofNullable((EntityRuntimeModel<E>) models.get(entity));
  }

  /** Returns an entity runtime model or reports that its generated provider is unavailable. */
  public <E> EntityRuntimeModel<E> require(EntityMeta<E> entity) {
    return find(entity)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "no generated runtime model is registered for entity '"
                        + entity.entityName()
                        + "'"));
  }

  public int size() {
    return models.size();
  }

  /** Returns an immutable snapshot of registered runtime models. */
  public Collection<EntityRuntimeModel<?>> models() {
    return models.values();
  }
}
