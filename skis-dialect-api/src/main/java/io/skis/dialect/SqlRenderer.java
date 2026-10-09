package io.skis.dialect;

import io.skis.sql.ast.QueryBlockAnalysis;
import io.skis.sql.ast.StatementAst;
import java.util.Objects;

/**
 * Converts an immutable statement AST into a JDBC SQL template and ordered parameter slots.
 *
 * <p>Implementations exposed to direct callers must run {@link
 * io.skis.sql.ast.SemanticValidator#validateComplete(StatementAst)} before emitting SQL.
 */
@FunctionalInterface
public interface SqlRenderer {

  /** Renders a statement or fails when it uses an unsupported node or dialect feature. */
  RenderedSql render(StatementAst statement);

  /**
   * Renders a query whose semantic and dialect validation already completed with the supplied
   * immutable analysis.
   *
   * <p>The compatibility default delegates to {@link #render(StatementAst)}, so existing third-party
   * renderers remain source compatible and may retain their defensive validation. Framework
   * renderers should override this method and serialize the already validated structure without
   * resolving it again. The caller currently guarantees that the analysis belongs to the statement;
   * implementations must not reanalyze solely to prove that pairing.
   */
  default RenderedSql renderValidated(
      StatementAst statement, QueryBlockAnalysis analysis) {
    Objects.requireNonNull(analysis, "analysis");
    return render(statement);
  }
}
