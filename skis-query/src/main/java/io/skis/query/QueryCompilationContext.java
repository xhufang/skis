package io.skis.query;

import io.skis.dialect.Dialect;
import io.skis.dialect.RenderedSql;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.QueryBlockAnalysis;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import io.skis.sql.ast.StatementAst;
import java.util.Objects;

/** Immutable hand-off between semantic analysis, dialect validation, rendering, and plan assembly. */
record QueryCompilationContext(StatementAst statement, QueryBlockAnalysis analysis) {

  QueryCompilationContext {
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(analysis, "analysis");
  }

  /** Resolves semantics once, then validates dialect capabilities against the same analysis. */
  static QueryCompilationContext prepare(StatementAst statement, Dialect dialect) {
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(dialect, "dialect");
    QueryBlockAnalysis analysis =
        switch (statement) {
          case SelectStatement select -> SemanticValidator.analyzeComplete(select);
          case CountAst count -> SemanticValidator.analyzeComplete(count);
          default ->
              throw new IllegalArgumentException(
                  "query compilation does not support statement node "
                      + statement.getClass().getName());
        };
    dialect.validate(statement, analysis);
    return new QueryCompilationContext(statement, analysis);
  }

  /** Serializes the already analyzed and dialect-validated query without resolving it again. */
  RenderedSql render(Dialect dialect) {
    return Objects.requireNonNull(dialect, "dialect")
        .renderer()
        .renderValidated(statement, analysis);
  }
}
