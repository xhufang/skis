package io.skis.runtime;

import io.skis.core.ExecutionOptions;
import io.skis.core.SkisConfigurationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.spi.ExecutionListener;
import io.skis.dialect.spi.Dialect;
import io.skis.jdbc.DataSourceConnectionProvider;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.RowReadContext;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.internal.MutationPlanCatalog;
import io.skis.mutation.internal.MutationRuntime;
import io.skis.query.QueryOperations;
import io.skis.query.internal.QueryPlanCatalog;
import io.skis.query.internal.QueryRuntime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** Assembly entry point for non-Spring applications and framework integrations. */
public final class Skis {

  private Skis() {}

  /** Creates an executor backed by a DataSource and an explicitly selected database dialect. */
  public static SkisExecutor create(DataSource dataSource, Dialect dialect) {
    return builder(dataSource).dialect(dialect).build();
  }

  /** Starts explicit assembly with the supplied DataSource as the connection owner. */
  public static Builder builder(DataSource dataSource) {
    return new Builder(
        new DataSourceConnectionProvider(Objects.requireNonNull(dataSource, "dataSource")));
  }

  /** Mutable one-time builder; the resulting executor is thread-safe and owns its runtime state. */
  public static final class Builder {

    private ConnectionProvider connectionProvider;
    private @Nullable Dialect dialect;
    private @Nullable ClassLoader classLoader;
    private @Nullable EntityRuntimeRegistry runtimeRegistry;
    private int planCacheMaximumSize = QueryPlanCatalog.DEFAULT_MAXIMUM_SIZE;
    private Duration planCacheExpireAfterAccess = QueryPlanCatalog.DEFAULT_EXPIRE_AFTER_ACCESS;
    private ExecutionOptions executionOptions = ExecutionOptions.NONE;
    private @Nullable List<ExecutionListener> listeners;

    private Builder(ConnectionProvider connectionProvider) {
      this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    }

    /** Replaces the DataSource-backed strategy with an explicit connection ownership strategy. */
    public Builder connectionProvider(ConnectionProvider connectionProvider) {
      this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
      return this;
    }

    /** Selects the required dialect used to compile and render all plans in this executor. */
    public Builder dialect(Dialect dialect) {
      this.dialect = Objects.requireNonNull(dialect, "dialect");
      return this;
    }

    /** Overrides the class loader used only for generated-index discovery at assembly time. */
    public Builder classLoader(ClassLoader classLoader) {
      this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
      return this;
    }

    /** Supplies generated runtime models directly for containers or AOT assembly. */
    public Builder runtimeModels(Collection<? extends EntityRuntimeModel<?>> runtimeModels) {
      try {
        this.runtimeRegistry =
            EntityRuntimeRegistry.of(Objects.requireNonNull(runtimeModels, "runtimeModels"));
      } catch (IllegalArgumentException failure) {
        throw SkisConfigurationException.of(
            SkisErrorCode.ASSEMBLY_MODEL_DUPLICATE,
            "supplied runtime model registry is invalid",
            failure);
      }
      return this;
    }

    /**
     * Sets immutable statement defaults for this executor and every transaction session it opens.
     *
     * <p>Per-statement options override these fields independently. Unset fields continue to use
     * JDBC driver defaults.
     */
    public Builder executionOptions(ExecutionOptions executionOptions) {
      this.executionOptions = Objects.requireNonNull(executionOptions, "executionOptions");
      return this;
    }

    /** Appends one synchronous read-only execution listener in registration order. */
    public Builder listener(ExecutionListener listener) {
      ExecutionListener required = Objects.requireNonNull(listener, "listener");
      if (listeners == null) {
        listeners = new ArrayList<>();
      }
      listeners.add(required);
      return this;
    }

    /**
     * Configures the maximum number of shared dynamic plans retained by the catalog-owned L1 and
     * the admission bound for concurrent distinct-key plan compilations.
     *
     * <p>Zero disables the shared L1 cache while preserving query-local reuse and full compilation.
     */
    public Builder planCacheMaximumSize(int maximumSize) {
      if (maximumSize < 0) {
        throw SkisConfigurationException.of(
            SkisErrorCode.CACHE_CONFIGURATION_INVALID, "planCacheMaximumSize must not be negative");
      }
      this.planCacheMaximumSize = maximumSize;
      return this;
    }

    /** Configures the shared dynamic-plan idle duration used for expire-after-access. */
    public Builder planCacheExpireAfterAccess(Duration expireAfterAccess) {
      Objects.requireNonNull(expireAfterAccess, "expireAfterAccess");
      if (expireAfterAccess.isZero() || expireAfterAccess.isNegative()) {
        throw SkisConfigurationException.of(
            SkisErrorCode.CACHE_CONFIGURATION_INVALID,
            "planCacheExpireAfterAccess must be positive");
      }
      this.planCacheExpireAfterAccess = expireAfterAccess;
      return this;
    }

    /** Loads any unspecified generated registries and creates the thread-safe executor. */
    public SkisExecutor build() {
      ExecutionListenerDispatcher.rejectReentrantCall();
      Dialect selectedDialect = dialect;
      if (selectedDialect == null) {
        throw SkisConfigurationException.of(
            SkisErrorCode.ASSEMBLY_CONFIGURATION_INVALID, "dialect must be configured");
      }
      EntityRuntimeRegistry selectedRegistry = runtimeRegistry;
      if (selectedRegistry == null) {
        selectedRegistry = EntityRuntimeModelLoader.load(resolveClassLoader(classLoader));
      }
      JdbcExecutor jdbcExecutor =
          new JdbcExecutor(
              connectionProvider,
              JdbcWriteContext.EMPTY,
              RowReadContext.EMPTY,
              executionOptions,
              selectedDialect.exceptionClassifier());
      ExecutionListenerDispatcher listenerDispatcher =
          listeners == null
              ? ExecutionListenerDispatcher.NONE
              : ExecutionListenerDispatcher.of(listeners);
      QueryPlanCatalog queryPlans =
          QueryRuntime.compile(
              selectedRegistry,
              selectedDialect,
              planCacheMaximumSize,
              planCacheExpireAfterAccess,
              listenerDispatcher);
      MutationPlanCatalog mutationPlans =
          MutationRuntime.compile(selectedRegistry, selectedDialect, listenerDispatcher);
      QueryOperations queries = queryPlans.bind(jdbcExecutor);
      MutationOperations mutations = mutationPlans.bind(jdbcExecutor);
      return new DefaultSkisExecutor(queries, mutations, jdbcExecutor, queryPlans, mutationPlans);
    }
  }

  private static ClassLoader resolveClassLoader(@Nullable ClassLoader configured) {
    if (configured != null) {
      return configured;
    }
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    if (context != null) {
      return context;
    }
    ClassLoader fallback = Skis.class.getClassLoader();
    if (fallback == null) {
      throw SkisConfigurationException.of(
          SkisErrorCode.ASSEMBLY_CONFIGURATION_INVALID,
          "no class loader is available for generated-index discovery; configure classLoader or runtimeModels");
    }
    return fallback;
  }
}
