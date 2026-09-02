package io.skis.query;

import io.skis.jdbc.CompiledQueryPlan;
import io.skis.sql.ast.StatementAst;
import java.util.Objects;

/** One value-independent plan plus the separate arguments for a terminal operation. */
record QueryCompilation<R>(CompiledQueryPlan<R, Object> plan, Object argument, StatementAst ast) {

  QueryCompilation {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(argument, "argument");
    Objects.requireNonNull(ast, "ast");
  }
}
