package io.skis.query;

import io.skis.dialect.Dialect;
import io.skis.jdbc.JdbcExecutor;
import io.skis.mapping.EntityRuntimeRegistry;
import java.time.Duration;
import java.util.Objects;

/** Infrastructure entry point used by runtime aggregators to assemble query operations. */
public final class QueryRuntime {

  private QueryRuntime() {}

  /** Creates immutable query operations backed by generated mappings and JDBC. */
  public static QueryOperations create(
      EntityRuntimeRegistry runtimeRegistry, Dialect dialect, JdbcExecutor jdbcExecutor) {
    return compile(runtimeRegistry, dialect)
        .bind(Objects.requireNonNull(jdbcExecutor, "jdbcExecutor"));
  }

  /** Compiles one shareable plan catalog for subsequent ordinary and transactional execution. */
  public static QueryPlanCatalog compile(EntityRuntimeRegistry runtimeRegistry, Dialect dialect) {
    return compile(
        runtimeRegistry,
        dialect,
        QueryPlanCatalog.DEFAULT_MAXIMUM_SIZE,
        QueryPlanCatalog.DEFAULT_EXPIRE_AFTER_ACCESS);
  }

  /** Compiles a plan catalog with explicit bounded-cache capacity and idle expiration. */
  public static QueryPlanCatalog compile(
      EntityRuntimeRegistry runtimeRegistry,
      Dialect dialect,
      int maximumSize,
      Duration expireAfterAccess) {
    return new QueryPlanCatalog(
        Objects.requireNonNull(runtimeRegistry, "runtimeRegistry"),
        Objects.requireNonNull(dialect, "dialect"),
        maximumSize,
        Objects.requireNonNull(expireAfterAccess, "expireAfterAccess"));
  }
}
