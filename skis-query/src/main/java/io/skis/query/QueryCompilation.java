package io.skis.query;

import io.skis.jdbc.internal.CompiledQueryPlan;
import io.skis.sql.ast.StatementAst;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One value-independent plan plus the separate arguments for a terminal operation. */
record QueryCompilation<R extends @Nullable Object>(
    CompiledQueryPlan<R, Object> plan, Object argument, StatementAst ast) {

  QueryCompilation {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(argument, "argument");
    Objects.requireNonNull(ast, "ast");
  }
}
