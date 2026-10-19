package io.skis.query;

import io.skis.dialect.spi.ResolvedStatement;
import io.skis.jdbc.internal.CompiledQueryPlan;
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
      Supplier<CompiledQueryPlan<R, Object>> compiler) {
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
}
