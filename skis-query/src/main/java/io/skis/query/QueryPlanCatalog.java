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
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Thread-safe catalog of entity Fast Path plans and owner boundary for shared query plans belonging
 * to one registry and dialect.
 *
 * <p>A compiled decoder can legitimately retain its result type's class loader. Containers that
 * unload or replace application/plugin classes must first quiesce affected query executions and
 * then call {@link #clearProjectionPlans()} before dropping that loader; entity-scoped model
 * replacement may use {@link
 * #invalidateProjectionPlans(EntityMeta)} when every affected entity is known.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class QueryPlanCatalog {

  /** Shared dynamic-plan capacity and distinct-key compilation admission bound; zero disables L1. */
  public static final int DEFAULT_MAXIMUM_SIZE = ProjectionPlanCache.DEFAULT_MAXIMUM_SIZE;

  /** Shared dynamic-plan idle duration. */
  public static final Duration DEFAULT_EXPIRE_AFTER_ACCESS =
      ProjectionPlanCache.DEFAULT_EXPIRE_AFTER_ACCESS;

  private final Map<EntityMeta<?>, EntityPlanSet<?>> planSets;
  private final ProjectionPlanCache sharedPlans;
  private final QueryPlanCompiler compiler;
  private final QueryPlanKey.IdentityScope planIdentities;
  private final Optional<QueryPlanKey.DialectIdentity> dialectIdentity;

  QueryPlanCatalog(
      EntityRuntimeRegistry runtimeRegistry,
      Dialect dialect,
      int maximumSize,
      Duration expireAfterAccess) {
    Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
    Dialect requiredDialect = Objects.requireNonNull(dialect, "dialect");
    QueryPlanCompiler compiler = new QueryPlanCompiler(runtimeRegistry, requiredDialect);
    this.compiler = compiler;
    this.planIdentities = new QueryPlanKey.IdentityScope(runtimeRegistry);
    this.dialectIdentity = QueryPlanKey.DialectIdentity.from(requiredDialect);
    this.sharedPlans = new ProjectionPlanCache(maximumSize, expireAfterAccess, System::nanoTime);
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

  /** Returns the shared dynamic-plan cache snapshot. */
  public QueryPlanCacheStatistics projectionPlanCacheStatistics() {
    return sharedPlans.statistics();
  }

  /**
   * Clears every shared dynamic plan without resetting cumulative activity counters.
   *
   * <p>After affected query executions have been quiesced, this is the required lifecycle cleanup
   * before unloading a result-type/plugin class loader.
   */
  public void clearProjectionPlans() {
    sharedPlans.clear();
  }

  /**
   * Invalidates shared plans depending on one canonical registered entity and returns the number
   * actually removed. An unregistered metadata identity has no plans in this catalog and returns
   * zero.
   */
  public int invalidateProjectionPlans(EntityMeta<?> entity) {
    EntityMeta<?> requiredEntity = Objects.requireNonNull(entity, "entity");
    return planSets.containsKey(requiredEntity) ? sharedPlans.invalidate(requiredEntity) : 0;
  }

  <R> CachedQueryPlan<R> sharedPlan(
      QueryPlanKey key,
      QueryPlanDependencies dependencies,
      Supplier<? extends CachedQueryPlan<R>> compiler) {
    QueryPlanDependencies requiredDependencies =
        Objects.requireNonNull(dependencies, "dependencies");
    for (EntityMeta<?> entity : requiredDependencies.entities()) {
      if (!planSets.containsKey(entity)) {
        throw new QueryValidationException(
            "query plan dependency '"
                + entity.entityName()
                + "' is not registered in this plan catalog");
      }
    }
    return sharedPlans.getOrCompile(key, requiredDependencies, compiler);
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

  QueryPlanCompiler compiler() {
    return compiler;
  }

  QueryPlanKey.IdentityScope planIdentities() {
    return planIdentities;
  }

  Optional<QueryPlanKey.DialectIdentity> dialectIdentity() {
    return dialectIdentity;
  }

  private static <E> EntityPlanSet<E> createPlanSet(
      EntityRuntimeModel<E> model, QueryPlanCompiler compiler) {
    return new EntityPlanSet<>(model, compiler);
  }
}
