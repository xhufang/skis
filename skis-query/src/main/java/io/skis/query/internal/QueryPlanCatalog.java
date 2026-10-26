package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisConfigurationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.dialect.spi.Dialect;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.metadata.EntityMeta;
import io.skis.query.QueryOperations;
import io.skis.query.QueryPlanCacheStatistics;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe catalog of entity Fast Path plans and owner boundary for shared query plans belonging
 * to one registry and dialect.
 *
 * <p>Ordinary query terminals share value-independent plans through this catalog; bindings created
 * by {@link #bind(JdbcExecutor)} use the same cache, including transaction-scoped executors.
 *
 * <p>A compiled decoder can legitimately retain its result type's class loader. Containers that
 * unload or replace application/plugin classes must first quiesce affected query executions and
 * then release affected query objects and call {@link #clearProjectionPlans()} before dropping that
 * loader. Entity-scoped cleanup may use {@link #invalidateProjectionPlans(EntityMeta)} when every
 * affected entity is known. Runtime registry or Codec configuration changes require a new catalog;
 * shared invalidation does not replace mappings or revoke existing query-local plans.
 */
public final class QueryPlanCatalog {

  /**
   * Shared dynamic-plan capacity and distinct-key compilation admission bound; zero disables L1.
   */
  public static final int DEFAULT_MAXIMUM_SIZE = ProjectionPlanCache.DEFAULT_MAXIMUM_SIZE;

  /** Shared dynamic-plan idle duration. */
  public static final Duration DEFAULT_EXPIRE_AFTER_ACCESS =
      ProjectionPlanCache.DEFAULT_EXPIRE_AFTER_ACCESS;

  private final Map<EntityMeta<?>, EntityPlanSet<?>> planSets;
  private final ProjectionPlanCache sharedPlans;
  private final QueryPlanCompiler compiler;
  private final ExecutionListenerDispatcher listenerDispatcher;
  private final String dialectId;

  QueryPlanCatalog(
      EntityRuntimeRegistry runtimeRegistry,
      Dialect dialect,
      int maximumSize,
      Duration expireAfterAccess,
      ExecutionListenerDispatcher listenerDispatcher) {
    Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
    Dialect requiredDialect = Objects.requireNonNull(dialect, "dialect");
    this.listenerDispatcher = Objects.requireNonNull(listenerDispatcher, "listenerDispatcher");
    this.dialectId = requiredDialect.id();
    QueryPlanKey.IdentityScope planIdentities = new QueryPlanKey.IdentityScope(runtimeRegistry);
    Optional<QueryPlanKey.DialectIdentity> dialectIdentity =
        QueryPlanKey.DialectIdentity.from(requiredDialect);
    this.sharedPlans = new ProjectionPlanCache(maximumSize, expireAfterAccess, System::nanoTime);
    QueryPlanKeyAssembler keys =
        new QueryPlanKeyAssembler(
            runtimeRegistry, planIdentities, dialectIdentity, maximumSize != 0);
    QueryPlanCompiler compiler =
        new QueryPlanCompiler(
            runtimeRegistry, requiredDialect, new QueryPlanResolver(sharedPlans, keys));
    this.compiler = compiler;
    Map<EntityMeta<?>, EntityPlanSet<?>> indexed = new IdentityHashMap<>();
    for (EntityRuntimeModel<?> model : runtimeRegistry.models()) {
      EntityPlanSet<?> previous =
          indexed.put(model.entity(), createPlanSet(model, compiler, this.listenerDispatcher));
      if (previous != null) {
        throw SkisConfigurationException.of(
            SkisErrorCode.ASSEMBLY_MODEL_DUPLICATE,
            "duplicate query plan set for entity '" + model.entity().entityName() + "'");
      }
    }
    this.planSets = Collections.unmodifiableMap(indexed);
  }

  QueryPlanCatalog(
      EntityRuntimeRegistry runtimeRegistry,
      Dialect dialect,
      int maximumSize,
      Duration expireAfterAccess) {
    this(
        runtimeRegistry, dialect, maximumSize, expireAfterAccess, ExecutionListenerDispatcher.NONE);
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
   * Clears every shared dynamic plan without resetting cumulative activity counters. Existing query
   * objects retain their immutable L0 plans; fresh queries look up or compile again.
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

  @SuppressWarnings("unchecked")
  <E> EntityPlanSet<E> require(EntityMeta<E> entity) {
    EntityPlanSet<?> plans = planSets.get(Objects.requireNonNull(entity, "entity"));
    if (plans == null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_METADATA_REQUIRED,
          "no generated runtime model is registered for entity '" + entity.entityName() + "'");
    }
    return (EntityPlanSet<E>) plans;
  }

  QueryPlanCompiler compiler() {
    return compiler;
  }

  ExecutionListenerDispatcher listenerDispatcher() {
    return listenerDispatcher;
  }

  String dialectId() {
    return dialectId;
  }

  private static <E> EntityPlanSet<E> createPlanSet(
      EntityRuntimeModel<E> model,
      QueryPlanCompiler compiler,
      ExecutionListenerDispatcher listenerDispatcher) {
    return new EntityPlanSet<>(model, compiler, listenerDispatcher, compiler.dialectId());
  }
}
