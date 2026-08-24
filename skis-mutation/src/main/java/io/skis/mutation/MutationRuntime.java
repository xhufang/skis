package io.skis.mutation;

import io.skis.dialect.Dialect;
import io.skis.jdbc.JdbcExecutor;
import io.skis.mapping.EntityRuntimeRegistry;
import java.util.Objects;

/** Infrastructure entry point used by runtime aggregators to assemble mutation operations. */
public final class MutationRuntime {

  private MutationRuntime() {}

  /** Creates immutable mutation operations backed by generated binders and JDBC. */
  public static MutationOperations create(
      EntityRuntimeRegistry runtimeRegistry, Dialect dialect, JdbcExecutor jdbcExecutor) {
    return compile(runtimeRegistry, dialect).bind(
        Objects.requireNonNull(jdbcExecutor, "jdbcExecutor"));
  }

  /** Compiles one shareable plan catalog for subsequent ordinary and transactional execution. */
  public static MutationPlanCatalog compile(
      EntityRuntimeRegistry runtimeRegistry, Dialect dialect) {
    return new MutationPlanCatalog(
        Objects.requireNonNull(runtimeRegistry, "runtimeRegistry"),
        Objects.requireNonNull(dialect, "dialect"));
  }
}
