package io.skis.query.internal;

import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.dialect.spi.Dialect;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.query.QueryOperations;
import java.time.Duration;
import java.util.Objects;

/** Infrastructure entry point used by runtime aggregators to assemble query operations. */
public final class QueryRuntime {

  private QueryRuntime() {}

  /** Creates immutable query operations backed by generated mappings and JDBC. */
  public static QueryOperations create(
      EntityRuntimeRegistry runtimeRegistry, Dialect dialect, JdbcExecutor jdbcExecutor) {
    ExecutionListenerDispatcher.rejectReentrantCall();
    return compile(runtimeRegistry, dialect)
        .bind(Objects.requireNonNull(jdbcExecutor, "jdbcExecutor"));
  }

  /** Compiles one shareable plan catalog for subsequent ordinary and transactional execution. */
  public static QueryPlanCatalog compile(EntityRuntimeRegistry runtimeRegistry, Dialect dialect) {
    ExecutionListenerDispatcher.rejectReentrantCall();
    return compile(
        runtimeRegistry,
        dialect,
        QueryPlanCatalog.DEFAULT_MAXIMUM_SIZE,
        QueryPlanCatalog.DEFAULT_EXPIRE_AFTER_ACCESS,
        ExecutionListenerDispatcher.NONE);
  }

  /**
   * Compiles a plan catalog with explicit bounded-cache capacity and idle expiration.
   *
   * <p>A {@code maximumSize} of zero disables the shared L1 cache while retaining query-local L0
   * reuse and full L2 compilation.
   */
  public static QueryPlanCatalog compile(
      EntityRuntimeRegistry runtimeRegistry,
      Dialect dialect,
      int maximumSize,
      Duration expireAfterAccess) {
    ExecutionListenerDispatcher.rejectReentrantCall();
    return compile(
        runtimeRegistry, dialect, maximumSize, expireAfterAccess, ExecutionListenerDispatcher.NONE);
  }

  /** Internal listener-aware catalog assembly used by the runtime facade. */
  public static QueryPlanCatalog compile(
      EntityRuntimeRegistry runtimeRegistry,
      Dialect dialect,
      int maximumSize,
      Duration expireAfterAccess,
      ExecutionListenerDispatcher listenerDispatcher) {
    ExecutionListenerDispatcher.rejectReentrantCall();
    return new QueryPlanCatalog(
        Objects.requireNonNull(runtimeRegistry, "runtimeRegistry"),
        Objects.requireNonNull(dialect, "dialect"),
        maximumSize,
        Objects.requireNonNull(expireAfterAccess, "expireAfterAccess"),
        Objects.requireNonNull(listenerDispatcher, "listenerDispatcher"));
  }
}
