package io.skis.dialect.h2;

import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.RenderedSql;
import io.skis.dialect.support.SqlCompiler;
import io.skis.dialect.support.SqlTemplate;
import io.skis.sql.ast.StatementAst;

/** Test-side controlled compilation facade for H2 SQL golden assertions. */
final class H2TestRendering {

  private H2TestRendering() {}

  static RenderedSql render(StatementAst statement) {
    return render(H2Dialect.INSTANCE, statement);
  }

  static RenderedSql render(Dialect dialect, StatementAst statement) {
    SqlTemplate template = SqlCompiler.compile(statement, dialect);
    return new RenderedSql(template.sql(), template.parameterLayout().slots());
  }
}
