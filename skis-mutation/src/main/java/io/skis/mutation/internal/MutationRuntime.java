package io.skis.mutation.internal;

import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.dialect.spi.Dialect;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.SnowflakeIdGenerator;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Infrastructure entry point used by runtime aggregators to assemble mutation operations. */
public final class MutationRuntime {

  private MutationRuntime() {}

  /** Creates immutable mutation operations backed by generated binders and JDBC. */
  public static MutationOperations create(
      EntityRuntimeRegistry runtimeRegistry, Dialect dialect, JdbcExecutor jdbcExecutor) {
    ExecutionListenerDispatcher.rejectReentrantCall();
    return compile(runtimeRegistry, dialect)
        .bind(Objects.requireNonNull(jdbcExecutor, "jdbcExecutor"));
  }

  /** Compiles one shareable plan catalog for subsequent ordinary and transactional execution. */
  public static MutationPlanCatalog compile(
      EntityRuntimeRegistry runtimeRegistry, Dialect dialect) {
    ExecutionListenerDispatcher.rejectReentrantCall();
    return compile(runtimeRegistry, dialect, ExecutionListenerDispatcher.NONE);
  }

  /** Internal listener-aware catalog assembly used by the runtime facade. */
  public static MutationPlanCatalog compile(
      EntityRuntimeRegistry runtimeRegistry,
      Dialect dialect,
      ExecutionListenerDispatcher listenerDispatcher) {
    return compile(runtimeRegistry, dialect, listenerDispatcher, null);
  }

  /** Assembles shared application ID generation together with the immutable plan catalog. */
  public static MutationPlanCatalog compile(
      EntityRuntimeRegistry runtimeRegistry,
      Dialect dialect,
      ExecutionListenerDispatcher listenerDispatcher,
      @Nullable SnowflakeIdGenerator snowflakeIds) {
    ExecutionListenerDispatcher.rejectReentrantCall();
    return new MutationPlanCatalog(
        Objects.requireNonNull(runtimeRegistry, "runtimeRegistry"),
        Objects.requireNonNull(dialect, "dialect"),
        Objects.requireNonNull(listenerDispatcher, "listenerDispatcher"),
        snowflakeIds);
  }
}
