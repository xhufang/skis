package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.RenderedSql;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.sql.ast.StatementAst;
import java.util.List;
import org.junit.jupiter.api.Test;

class DialectSpiOnlyConsumerTest {

  @Test
  void implementsDialectUsingOnlyThePublicApiArtifact() {
    ExternalDialect dialect = new ExternalDialect();

    assertEquals("external-spi-only", dialect.id());
    assertEquals("\"select\"", dialect.identifierRules().quote("select"));
    assertFalse(dialect.hasStablePlanCacheIdentity());
    SqlRenderer renderer = dialect.renderer();
    assertSame(renderer, dialect.renderer());
    assertEquals(
        new RenderedSql("SELECT 1", List.of()),
        renderer.render(new StatementAst() {}));
  }

  private static final class ExternalDialect implements Dialect {

    private static final DialectCapabilities CAPABILITIES =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    private final SqlRenderer renderer =
        statement -> new RenderedSql("SELECT 1", List.of());

    @Override
    public String id() {
      return "external-spi-only";
    }

    @Override
    public IdentifierRules identifierRules() {
      return identifier -> "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public DialectCapabilities capabilities() {
      return CAPABILITIES;
    }

    @Override
    public SqlRenderer renderer() {
      return renderer;
    }
  }
}
