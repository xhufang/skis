package io.skis.query.internal;

import io.skis.jdbc.internal.CompiledQueryPlan;
import io.skis.sql.ast.StatementAst;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One immutable, value-independent query plan that is safe to retain in the shared L1 cache. */
record CachedQueryPlan<R extends @Nullable Object>(CompiledQueryPlan<R, Object> plan) {

  CachedQueryPlan {
    Objects.requireNonNull(plan, "plan");
  }

  /** Drops the invocation-local argument and AST while retaining only the shareable plan. */
  static <R extends @Nullable Object> CachedQueryPlan<R> from(QueryCompilation<R> compilation) {
    QueryCompilation<R> compiled = Objects.requireNonNull(compilation, "compilation");
    return new CachedQueryPlan<>(compiled.plan());
  }

  /** Reattaches one invocation's argument and AST without retaining either in the cached value. */
  QueryCompilation<R> bind(Object argument, StatementAst ast) {
    return new QueryCompilation<>(
        plan, Objects.requireNonNull(argument, "argument"), Objects.requireNonNull(ast, "ast"));
  }
}
