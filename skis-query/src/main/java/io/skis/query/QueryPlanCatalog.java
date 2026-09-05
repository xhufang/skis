package io.skis.query;

import io.skis.dialect.Dialect;
import io.skis.jdbc.JdbcExecutor;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.metadata.EntityMeta;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Thread-safe catalog of entity Fast Path plans for one registry and dialect. */
public final class QueryPlanCatalog {

  /** Legacy dynamic-plan capacity retained until the general cache lands in 0.2.7. */
  public static final int DEFAULT_MAXIMUM_SIZE = ProjectionPlanCache.DEFAULT_MAXIMUM_SIZE;

  /** Legacy dynamic-plan idle duration retained until the general cache lands in 0.2.7. */
  public static final Duration DEFAULT_EXPIRE_AFTER_ACCESS =
      ProjectionPlanCache.DEFAULT_EXPIRE_AFTER_ACCESS;

  private final Map<EntityMeta<?>, EntityPlanSet<?>> planSets;
  private final ProjectionPlanCache projectionPlans;

  QueryPlanCatalog(
      EntityRuntimeRegistry runtimeRegistry,
      Dialect dialect,
      int maximumSize,
      Duration expireAfterAccess) {
    Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
    QueryPlanCompiler compiler =
        new QueryPlanCompiler(runtimeRegistry, Objects.requireNonNull(dialect, "dialect"));
    this.projectionPlans =
        new ProjectionPlanCache(maximumSize, expireAfterAccess, System::nanoTime);
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

  /**
   * Returns the shared dynamic-plan cache snapshot.
   *
   * <p>Generated projection plans are query-local in 0.2.4, so this snapshot is empty until the
   * general structural cache replaces the removed entity-bound projection cache.
   */
  public QueryPlanCacheStatistics projectionPlanCacheStatistics() {
    return projectionPlans.statistics();
  }

  /** Clears shared dynamic plans; this is a no-op for query-local 0.2.4 projection plans. */
  public void clearProjectionPlans() {
    projectionPlans.clear();
  }

  /** Invalidates shared plans for one entity; returns zero for query-local 0.2.4 projections. */
  public int invalidateProjectionPlans(EntityMeta<?> entity) {
    return projectionPlans.invalidate(entity);
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
