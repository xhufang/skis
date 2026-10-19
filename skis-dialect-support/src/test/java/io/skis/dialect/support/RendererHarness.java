package io.skis.dialect.support;

import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectLowering;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.RenderedSql;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.sql.ast.StatementAst;
import java.util.Locale;
import java.util.Objects;

/** Test-side facade that exercises the full controlled compiler and exposes SQL plus layout. */
final class RendererHarness {

  private final Dialect dialect;

  private RendererHarness(Dialect dialect) {
    this.dialect = Objects.requireNonNull(dialect, "dialect");
  }

  static RendererHarness of(String id, DialectCapabilities capabilities) {
    return new RendererHarness(new TestDialect(id, capabilities));
  }

  static RendererHarness of(Dialect dialect) {
    return new RendererHarness(dialect);
  }

  SqlTemplate compile(StatementAst statement) {
    return SqlCompiler.compile(statement, dialect);
  }

  RenderedSql render(StatementAst statement) {
    SqlTemplate template = compile(statement);
    return new RenderedSql(template.sql(), template.parameterLayout().slots());
  }

  Dialect dialect() {
    return dialect;
  }

  SqlRenderer renderer() {
    return dialect.renderer();
  }

  private static final class TestDialect implements Dialect {

    private final String id;
    private final DialectCapabilities capabilities;
    private final DialectLowering lowering;
    private final SqlRenderer renderer;

    private TestDialect(String id, DialectCapabilities capabilities) {
      this.id = Objects.requireNonNull(id, "id");
      if (id.isBlank() || !id.equals(id.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("test dialect id must be lowercase and non-blank");
      }
      this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
      this.lowering = StandardSqlLowering.of(capabilities);
      this.renderer = new StandardSqlRenderer(this);
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public IdentifierRules identifierRules() {
      return StandardIdentifierRules.INSTANCE;
    }

    @Override
    public DialectCapabilities capabilities() {
      return capabilities;
    }

    @Override
    public DialectLowering lowering() {
      return lowering;
    }

    @Override
    public SqlRenderer renderer() {
      return renderer;
    }
  }
}
