package io.skis.query;

import io.skis.dialect.Dialect;
import io.skis.jdbc.JdbcExecutor;
import io.skis.mapping.EntityRuntimeRegistry;
import java.util.Objects;

/** Infrastructure entry point used by runtime aggregators to assemble query operations. */
public final class QueryRuntime {

  private QueryRuntime() {}

  /** Creates immutable query operations backed by generated mappings and JDBC. */
  public static QueryOperations create(
      EntityRuntimeRegistry runtimeRegistry, Dialect dialect, JdbcExecutor jdbcExecutor) {
    return new DefaultQueryOperations(
        Objects.requireNonNull(runtimeRegistry, "runtimeRegistry"),
        Objects.requireNonNull(dialect, "dialect"),
        Objects.requireNonNull(jdbcExecutor, "jdbcExecutor"));
  }
}
