package io.skis.dialect;

import io.skis.sql.ast.StatementAst;

/** Converts an immutable statement AST into a JDBC SQL template and ordered parameter slots. */
@FunctionalInterface
public interface SqlRenderer {

  /** Renders a statement or fails when it uses an unsupported node or dialect feature. */
  RenderedSql render(StatementAst statement);
}
