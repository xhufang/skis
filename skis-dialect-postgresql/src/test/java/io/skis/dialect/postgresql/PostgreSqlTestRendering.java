package io.skis.dialect.postgresql;

import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.RenderedSql;
import io.skis.dialect.support.SqlCompiler;
import io.skis.dialect.support.SqlTemplate;
import io.skis.sql.ast.StatementAst;

/** Test-side controlled compilation facade for PostgreSQL SQL golden assertions. */
final class PostgreSqlTestRendering {

  private PostgreSqlTestRendering() {}

  static RenderedSql render(StatementAst statement) {
    return render(PostgreSqlDialect.INSTANCE, statement);
  }

  static RenderedSql render(Dialect dialect, StatementAst statement) {
    SqlTemplate template = SqlCompiler.compile(statement, dialect);
    return new RenderedSql(template.sql(), template.parameterLayout().slots());
  }
}
