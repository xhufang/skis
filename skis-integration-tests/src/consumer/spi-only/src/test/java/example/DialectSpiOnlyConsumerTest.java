package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.RenderInput;
import io.skis.dialect.spi.SqlRenderer;
import org.junit.jupiter.api.Test;

class DialectSpiOnlyConsumerTest {

  @Test
  void implementsDialectUsingOnlyThePublicApiArtifact() {
    ExternalDialect dialect = new ExternalDialect();

    assertEquals("external-spi-only", dialect.id());
    assertEquals("\"select\"", dialect.identifierRules().quote("select"));
    assertFalse(dialect.hasStablePlanCacheIdentity());
    assertNotNull(dialect.lowering());
    SqlRenderer renderer = dialect.renderer();
    assertSame(renderer, dialect.renderer());
  }

  private static final class ExternalDialect implements Dialect {

    private static final DialectCapabilities CAPABILITIES =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    private final SqlRenderer renderer = input -> "SELECT 1";

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
