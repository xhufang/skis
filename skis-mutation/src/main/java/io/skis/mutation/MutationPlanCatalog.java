package io.skis.mutation;

import io.skis.dialect.Dialect;
import io.skis.jdbc.JdbcExecutor;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.metadata.EntityMeta;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Thread-safe catalog of entity mutation Fast Paths compiled once per registry and dialect. */
public final class MutationPlanCatalog {

  private final Map<EntityMeta<?>, EntityMutationPlanSet<?>> planSets;

  MutationPlanCatalog(EntityRuntimeRegistry runtimeRegistry, Dialect dialect) {
    Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
    MutationPlanCompiler compiler =
        new MutationPlanCompiler(Objects.requireNonNull(dialect, "dialect"));
    Map<EntityMeta<?>, EntityMutationPlanSet<?>> indexed = new IdentityHashMap<>();
    for (EntityRuntimeModel<?> model : runtimeRegistry.models()) {
      if (model.entity().readOnly()) {
        continue;
      }
      EntityMutationPlanSet<?> previous = indexed.put(model.entity(), compile(model, compiler));
      if (previous != null) {
        throw new MutationException(
            "duplicate mutation plan set for entity '" + model.entity().entityName() + "'");
      }
    }
    this.planSets = Collections.unmodifiableMap(indexed);
  }

  /** Binds the shared plans to a JDBC executor without recompiling SQL. */
  public MutationOperations bind(JdbcExecutor jdbcExecutor) {
    return new DefaultMutationOperations(
        this, Objects.requireNonNull(jdbcExecutor, "jdbcExecutor"));
  }

  @SuppressWarnings("unchecked")
  <E> EntityMutationPlanSet<E> require(EntityMeta<E> entity) {
    Objects.requireNonNull(entity, "entity");
    if (entity.readOnly()) {
      throw new MutationException(
          "read-only entity '" + entity.entityName() + "' cannot be mutated");
    }
    EntityMutationPlanSet<?> plans = planSets.get(entity);
    if (plans == null) {
      throw new MutationException(
          "no generated mutation model is registered for entity '" + entity.entityName() + "'");
    }
    return (EntityMutationPlanSet<E>) plans;
  }

  private static <E> EntityMutationPlanSet<E> compile(
      EntityRuntimeModel<E> model, MutationPlanCompiler compiler) {
    return compiler.compile(model);
  }
}
