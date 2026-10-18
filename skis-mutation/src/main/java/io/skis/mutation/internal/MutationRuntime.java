package io.skis.mutation.internal;

import io.skis.dialect.spi.Dialect;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mutation.MutationOperations;
import java.util.Objects;

/** Infrastructure entry point used by runtime aggregators to assemble mutation operations. */
public final class MutationRuntime {

  private MutationRuntime() {}

  /** Creates immutable mutation operations backed by generated binders and JDBC. */
  public static MutationOperations create(
      EntityRuntimeRegistry runtimeRegistry, Dialect dialect, JdbcExecutor jdbcExecutor) {
    return compile(runtimeRegistry, dialect)
        .bind(Objects.requireNonNull(jdbcExecutor, "jdbcExecutor"));
  }

  /** Compiles one shareable plan catalog for subsequent ordinary and transactional execution. */
  public static MutationPlanCatalog compile(
      EntityRuntimeRegistry runtimeRegistry, Dialect dialect) {
    return new MutationPlanCatalog(
        Objects.requireNonNull(runtimeRegistry, "runtimeRegistry"),
        Objects.requireNonNull(dialect, "dialect"));
  }
}
