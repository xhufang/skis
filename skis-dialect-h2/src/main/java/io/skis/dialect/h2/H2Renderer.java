package io.skis.dialect.h2;

import io.skis.dialect.RenderedSql;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardSqlRenderer;
import io.skis.sql.ast.StatementAst;

/** H2 renderer for the portable SELECT/Join and single-table mutation subset. */
public final class H2Renderer implements SqlRenderer {

  /** Stateless shared renderer instance. */
  public static final H2Renderer INSTANCE = new H2Renderer();

  private final SqlRenderer delegate;

  private H2Renderer() {
    this.delegate =
        new StandardSqlRenderer(
            H2Dialect.ID, H2Dialect.INSTANCE.identifierRules(), H2Dialect.INSTANCE.capabilities());
  }

  @Override
  public RenderedSql render(StatementAst statement) {
    return delegate.render(statement);
  }
}
