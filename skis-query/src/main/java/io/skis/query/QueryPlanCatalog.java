package io.skis.query;

import io.skis.dialect.Dialect;
import io.skis.jdbc.JdbcExecutor;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.metadata.EntityMeta;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Thread-safe catalog sharing eager and bounded lazy query plans for one registry and dialect. */
public final class QueryPlanCatalog {

  private final Map<EntityMeta<?>, EntityPlanSet<?>> planSets;

  QueryPlanCatalog(EntityRuntimeRegistry runtimeRegistry, Dialect dialect) {
    Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
    QueryPlanCompiler compiler = new QueryPlanCompiler(Objects.requireNonNull(dialect, "dialect"));
    Map<EntityMeta<?>, EntityPlanSet<?>> indexed = new IdentityHashMap<>();
    for (EntityRuntimeModel<?> model : runtimeRegistry.models()) {
      EntityPlanSet<?> previous = indexed.put(model.entity(), createPlanSet(model, compiler));
      if (previous != null) {
        throw new IllegalArgumentException(
            "duplicate query plan set for entity '" + model.entity().entityName() + "'");
      }
    }
    this.planSets = Collections.unmodifiableMap(indexed);
  }

  /** Binds the shared plans to a JDBC executor without recompiling SQL. */
  public QueryOperations bind(JdbcExecutor jdbcExecutor) {
    return new DefaultQueryOperations(this, Objects.requireNonNull(jdbcExecutor, "jdbcExecutor"));
  }

  @SuppressWarnings("unchecked")
  <E> EntityPlanSet<E> require(EntityMeta<E> entity) {
    EntityPlanSet<?> plans = planSets.get(Objects.requireNonNull(entity, "entity"));
    if (plans == null) {
      throw new QueryValidationException(
          "no generated runtime model is registered for entity '" + entity.entityName() + "'");
    }
    return (EntityPlanSet<E>) plans;
  }

  private static <E> EntityPlanSet<E> createPlanSet(
      EntityRuntimeModel<E> model, QueryPlanCompiler compiler) {
    return new EntityPlanSet<>(model, compiler);
  }
}
