package io.skis.query.internal;

import io.skis.core.SkisStatementKind;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.StatementPurpose;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.jdbc.internal.CompiledQueryPlan;
import io.skis.jdbc.internal.JdbcFingerprint;
import io.skis.query.Selectable;
import io.skis.sql.ast.StatementAst;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Catalog-owned L1/L2 routing; invocation ASTs, arguments and compiler suppliers are never cached.
 */
final class QueryPlanResolver {

  private final ProjectionPlanCache cache;
  private final QueryPlanKeyAssembler keys;

  QueryPlanResolver(ProjectionPlanCache cache, QueryPlanKeyAssembler keys) {
    this.cache = Objects.requireNonNull(cache, "cache");
    this.keys = Objects.requireNonNull(keys, "keys");
  }

  <R extends @Nullable Object> QueryCompilation<R> resolve(
      ResolvedStatement keyContext,
      StatementAst invocationAst,
      SelectedResult<?> selected,
      QueryPlanKey.PlanVariant variant,
      List<QueryPlanCompiler.LogicalParameter> parameters,
      List<@Nullable Selectable<?>> sources,
      Object argument,
      Supplier<CompiledQueryPlan<R, Object>> compiler,
      @Nullable CompilationObservation observation) {
    if (observation == null) {
      CachedQueryPlan<R> plan =
          switch (keys.assemble(keyContext, selected, variant, parameters, sources)) {
            case QueryPlanKeyAssembler.Cacheable eligible ->
                cache.getOrCompile(
                    eligible.key(),
                    eligible.dependencies(),
                    () -> new CachedQueryPlan<>(compiler.get()));
            case QueryPlanKeyAssembler.Bypass ignored -> new CachedQueryPlan<>(compiler.get());
          };
      return plan.bind(argument, invocationAst);
    }
    CachedQueryPlan<R> plan;
    try {
      var assembly = keys.assemble(keyContext, selected, variant, parameters, sources);
      observation.resolutionStarted();
      plan =
          switch (assembly) {
            case QueryPlanKeyAssembler.Cacheable eligible ->
                cache.getOrCompile(
                    eligible.key(),
                    eligible.dependencies(),
                    () -> new CachedQueryPlan<>(observation.compile(compiler)));
            case QueryPlanKeyAssembler.Bypass ignored ->
                new CachedQueryPlan<>(observation.compile(compiler));
          };
    } catch (RuntimeException | Error failure) {
      observation.completed(failure);
      throw failure;
    }
    observation.completed(null);
    return plan.bind(argument, invocationAst);
  }

  /** Tracks preparation and actual plan assembly, excluding cache lookup/admission/wait time. */
  static final class CompilationObservation {

    private final OperationObservation operation;
    private final StatementPurpose purpose;
    private final long startedNanos;
    private boolean resolving;
    private boolean invoked;
    private long preparationNanos;
    private long durationNanos;
    private @Nullable CompiledQueryPlan<?, Object> compiled;

    CompilationObservation(OperationObservation operation, StatementPurpose purpose) {
      this.operation = Objects.requireNonNull(operation, "operation");
      this.purpose = Objects.requireNonNull(purpose, "purpose");
      this.startedNanos = System.nanoTime();
    }

    void preparationFailed(Throwable failure) {
      if (!resolving) {
        durationNanos = Math.max(0L, System.nanoTime() - startedNanos);
        notifyCompleted(failure);
      }
    }

    private void resolutionStarted() {
      preparationNanos = Math.max(0L, System.nanoTime() - startedNanos);
      resolving = true;
    }

    private <R extends @Nullable Object> CompiledQueryPlan<R, Object> compile(
        Supplier<CompiledQueryPlan<R, Object>> compiler) {
      invoked = true;
      long assemblyStartedNanos = System.nanoTime();
      try {
        CompiledQueryPlan<R, Object> plan = compiler.get();
        compiled = plan;
        return plan;
      } finally {
        long assemblyNanos = Math.max(0L, System.nanoTime() - assemblyStartedNanos);
        long total = preparationNanos + assemblyNanos;
        durationNanos = total < 0L ? Long.MAX_VALUE : total;
      }
    }

    private void completed(@Nullable Throwable failure) {
      if (invoked) {
        notifyCompleted(failure);
      }
    }

    private void notifyCompleted(@Nullable Throwable failure) {
      CompiledQueryPlan<?, Object> plan = compiled;
      String sql = plan == null ? null : plan.sql();
      operation.compilationCompleted(
          purpose,
          SkisStatementKind.SELECT,
          durationNanos,
          sql == null ? null : JdbcFingerprint.of(sql),
          sql,
          failure);
    }
  }
}
