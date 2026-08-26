package io.skis.runtime;

import io.skis.dialect.Dialect;
import io.skis.jdbc.ConnectionProvider;
import io.skis.jdbc.DataSourceConnectionProvider;
import io.skis.jdbc.JdbcExecutor;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.MutationPlanCatalog;
import io.skis.mutation.MutationRuntime;
import io.skis.query.ProjectionRegistry;
import io.skis.query.QueryOperations;
import io.skis.query.QueryPlanCatalog;
import io.skis.query.QueryRuntime;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** Immutable assembly entry point used by non-Spring applications and future auto-configuration. */
public final class SkisExecutorFactory {

  private SkisExecutorFactory() {}

  /**
   * Creates an executor and automatically loads generated entities from the context class loader.
   */
  public static SkisExecutor create(ConnectionProvider connectionProvider, Dialect dialect) {
    return builder().connectionProvider(connectionProvider).dialect(dialect).build();
  }

  /** Creates an executor backed by a DataSource and the selected database dialect. */
  public static SkisExecutor create(DataSource dataSource, Dialect dialect) {
    return create(new DataSourceConnectionProvider(dataSource), dialect);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Mutable one-time builder; the resulting executor is thread-safe and owns its runtime state. */
  public static final class Builder {

    private @Nullable ConnectionProvider connectionProvider;
    private @Nullable Dialect dialect;
    private @Nullable ClassLoader classLoader;
    private @Nullable EntityRuntimeRegistry runtimeRegistry;
    private @Nullable ProjectionRegistry projectionRegistry;
    private int planCacheMaximumSize = QueryPlanCatalog.DEFAULT_MAXIMUM_SIZE;
    private Duration planCacheExpireAfterAccess = QueryPlanCatalog.DEFAULT_EXPIRE_AFTER_ACCESS;

    private Builder() {}

    public Builder connectionProvider(ConnectionProvider connectionProvider) {
      this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
      return this;
    }

    public Builder dataSource(DataSource dataSource) {
      return connectionProvider(new DataSourceConnectionProvider(dataSource));
    }

    public Builder dialect(Dialect dialect) {
      this.dialect = Objects.requireNonNull(dialect, "dialect");
      return this;
    }

    /** Overrides the class loader used only for generated-index discovery at assembly time. */
    public Builder classLoader(ClassLoader classLoader) {
      this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
      return this;
    }

    /** Supplies an already loaded immutable registry for containers, tests, or AOT assembly. */
    public Builder runtimeRegistry(EntityRuntimeRegistry runtimeRegistry) {
      this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
      return this;
    }

    /** Supplies an already loaded projection registry for containers, tests, or AOT assembly. */
    public Builder projectionRegistry(ProjectionRegistry projectionRegistry) {
      this.projectionRegistry = Objects.requireNonNull(projectionRegistry, "projectionRegistry");
      return this;
    }

    /** Sets the maximum number of dynamic projection plans shared by this executor. */
    public Builder planCacheMaximumSize(int maximumSize) {
      if (maximumSize < 1) {
        throw new IllegalArgumentException("planCacheMaximumSize must be positive");
      }
      this.planCacheMaximumSize = maximumSize;
      return this;
    }

    /** Sets how long an unused dynamic projection plan remains eligible for reuse. */
    public Builder planCacheExpireAfterAccess(Duration expireAfterAccess) {
      Objects.requireNonNull(expireAfterAccess, "expireAfterAccess");
      if (expireAfterAccess.isZero() || expireAfterAccess.isNegative()) {
        throw new IllegalArgumentException("planCacheExpireAfterAccess must be positive");
      }
      this.planCacheExpireAfterAccess = expireAfterAccess;
      return this;
    }

    public SkisExecutor build() {
      ConnectionProvider provider =
          Objects.requireNonNull(connectionProvider, "connectionProvider must be configured");
      Dialect selectedDialect = Objects.requireNonNull(dialect, "dialect must be configured");
      EntityRuntimeRegistry selectedRegistry = runtimeRegistry;
      if (selectedRegistry == null) {
        selectedRegistry = EntityRuntimeModelLoader.load(resolveClassLoader(classLoader));
      }
      ProjectionRegistry selectedProjections = projectionRegistry;
      if (selectedProjections == null) {
        selectedProjections = ProjectionModelLoader.load(resolveClassLoader(classLoader));
      }
      JdbcExecutor jdbcExecutor = new JdbcExecutor(provider);
      QueryPlanCatalog queryPlans =
          QueryRuntime.compile(
              selectedRegistry,
              selectedProjections,
              selectedDialect,
              planCacheMaximumSize,
              planCacheExpireAfterAccess);
      MutationPlanCatalog mutationPlans =
          MutationRuntime.compile(selectedRegistry, selectedDialect);
      QueryOperations queries = queryPlans.bind(jdbcExecutor);
      MutationOperations mutations = mutationPlans.bind(jdbcExecutor);
      return new DefaultSkisExecutor(queries, mutations, provider, queryPlans, mutationPlans);
    }
  }

  private static ClassLoader resolveClassLoader(@Nullable ClassLoader configured) {
    if (configured != null) {
      return configured;
    }
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    return context != null ? context : SkisExecutorFactory.class.getClassLoader();
  }
}
