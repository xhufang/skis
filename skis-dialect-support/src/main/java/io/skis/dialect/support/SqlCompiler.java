package io.skis.dialect.support;

import io.skis.core.SkisErrorCode;
import io.skis.dialect.SqlRenderException;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.RenderInput;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.sql.ast.StatementAst;
import java.util.Objects;

/**
 * Database-free safe compilation facade shared by direct SQL generation and normal query/mutation
 * compilation.
 *
 * <p>Both entry points execute the same controlled pipeline: semantic analysis, recursive dialect
 * capability and custom validation, dialect lowering, portable normalization, post-lowering
 * validation, and final parameter layout. The facade has no trusted flags, accepts no external
 * analysis, does not access a connection, and never reads invocation parameter values.
 */
public final class SqlCompiler {

  private SqlCompiler() {}

  /** Analyzes a raw statement and compiles it for the supplied dialect. */
  public static SqlTemplate compile(StatementAst statement, Dialect dialect) {
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(dialect, "dialect");
    ResolvedStatement resolved;
    try {
      resolved = ResolvedStatement.analyze(statement);
    } catch (IllegalArgumentException failure) {
      String detail = failure.getMessage();
      throw SqlRenderException.of(
          SkisErrorCode.DIALECT_INVALID_STRUCTURE,
          dialect.id(),
          "statement failed controlled analysis"
              + (detail == null || detail.isBlank() ? "" : ": " + detail));
    }
    return compile(resolved, dialect);
  }

  /**
   * Compiles an already resolved stage without repeating semantic analysis.
   *
   * <p>The stage must have been produced by {@link ResolvedStatement#analyze(StatementAst)} or by
   * the same pipeline. Returning raw AST text as a second compiler path is not supported.
   */
  public static SqlTemplate compile(ResolvedStatement resolved, Dialect dialect) {
    Objects.requireNonNull(resolved, "resolved");
    Objects.requireNonNull(dialect, "dialect");
    RenderInput input = RenderInput.prepare(resolved, dialect);
    String sql = dialect.renderer().render(input);
    return new SqlTemplate(sql, input.parameterLayout());
  }
}
