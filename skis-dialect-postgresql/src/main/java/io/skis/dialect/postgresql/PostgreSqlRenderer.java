package io.skis.dialect.postgresql;

import io.skis.dialect.RenderedSql;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardSqlRenderer;
import io.skis.sql.ast.StatementAst;

/** PostgreSQL renderer for the portable SELECT/Join and single-table mutation subset. */
public final class PostgreSqlRenderer implements SqlRenderer {

  /** Stateless shared renderer instance. */
  public static final PostgreSqlRenderer INSTANCE = new PostgreSqlRenderer();

  private final SqlRenderer delegate;

  private PostgreSqlRenderer() {
    this.delegate =
        new StandardSqlRenderer(
            PostgreSqlDialect.ID,
            PostgreSqlDialect.INSTANCE.identifierRules(),
            PostgreSqlDialect.INSTANCE.capabilities());
  }

  @Override
  public RenderedSql render(StatementAst statement) {
    return delegate.render(statement);
  }
}
