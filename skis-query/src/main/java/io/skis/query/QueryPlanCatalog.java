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

/** Thread-safe catalog sharing eager and bounded lazy query plans for one registry and dialect. */
public final class QueryPlanCatalog {

  /** Default maximum number of shared dynamic projection plans. */
  public static final int DEFAULT_MAXIMUM_SIZE = ProjectionPlanCache.DEFAULT_MAXIMUM_SIZE;

  /** Default idle duration after which a shared projection plan expires. */
  public static final Duration DEFAULT_EXPIRE_AFTER_ACCESS =
      ProjectionPlanCache.DEFAULT_EXPIRE_AFTER_ACCESS;

  private final Map<EntityMeta<?>, EntityPlanSet<?>> planSets;
  private final ProjectionPlanCache projectionPlans;
  private final ProjectionRegistry projectionRegistry;

  QueryPlanCatalog(EntityRuntimeRegistry runtimeRegistry, Dialect dialect) {
    this(
        runtimeRegistry,
        ProjectionRegistry.empty(),
        dialect,
        DEFAULT_MAXIMUM_SIZE,
        DEFAULT_EXPIRE_AFTER_ACCESS);
  }

  QueryPlanCatalog(
      EntityRuntimeRegistry runtimeRegistry,
      Dialect dialect,
      int maximumSize,
      Duration expireAfterAccess) {
    this(runtimeRegistry, ProjectionRegistry.empty(), dialect, maximumSize, expireAfterAccess);
  }

  QueryPlanCatalog(
      EntityRuntimeRegistry runtimeRegistry,
      ProjectionRegistry projectionRegistry,
      Dialect dialect,
      int maximumSize,
      Duration expireAfterAccess) {
    Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
    this.projectionRegistry = Objects.requireNonNull(projectionRegistry, "projectionRegistry");
    QueryPlanCompiler compiler = new QueryPlanCompiler(Objects.requireNonNull(dialect, "dialect"));
    this.projectionPlans = new ProjectionPlanCache(maximumSize, expireAfterAccess);
    Map<EntityMeta<?>, EntityPlanSet<?>> indexed = new IdentityHashMap<>();
    for (EntityRuntimeModel<?> model : runtimeRegistry.models()) {
      EntityPlanSet<?> previous =
          indexed.put(model.entity(), createPlanSet(model, compiler, this.projectionPlans));
      if (previous != null) {
        throw new IllegalArgumentException(
            "duplicate query plan set for entity '" + model.entity().entityName() + "'");
      }
    }
    this.planSets = Collections.unmodifiableMap(indexed);
  }

  /** Binds the shared plans to a JDBC executor without recompiling SQL. */
  public QueryOperations bind(JdbcExecutor jdbcExecutor) {
    return new DefaultQueryOperations(
        this, projectionRegistry, Objects.requireNonNull(jdbcExecutor, "jdbcExecutor"));
  }

  /** Returns an immutable snapshot of shared projection-plan cache activity. */
  public QueryPlanCacheStatistics projectionPlanCacheStatistics() {
    return projectionPlans.statistics();
  }

  /** Explicitly clears every shared projection plan while preserving cumulative statistics. */
  public void clearProjectionPlans() {
    projectionPlans.clear();
  }

  /** Invalidates shared projection plans owned by one canonical entity metadata instance. */
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
      EntityRuntimeModel<E> model,
      QueryPlanCompiler compiler,
      ProjectionPlanCache projectionPlans) {
    return new EntityPlanSet<>(model, compiler, projectionPlans);
  }
}
