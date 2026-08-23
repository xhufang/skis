package io.skis.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.sql.ast.ParameterSlot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DialectValueTypesTest {

  @Test
  void quotesEachIdentifierComponentAndEscapesEmbeddedQuotes() {
    IdentifierRules rules = StandardIdentifierRules.INSTANCE;

    assertEquals("\"pet\"", rules.quote("pet"));
    assertEquals("\"select\"", rules.quote("select"));
    assertEquals("\"pet\"\"name\"", rules.quote("pet\"name"));
    assertEquals("\"pet; DROP TABLE audit\"", rules.quote("pet; DROP TABLE audit"));
    assertThrows(NullPointerException.class, () -> rules.quote(null));
    assertThrows(IllegalArgumentException.class, () -> rules.quote(" "));
    assertThrows(IllegalArgumentException.class, () -> rules.quote("pet\0name"));
  }

  @Test
  void capabilitiesAreImmutableAndFeatureBased() {
    DialectCapabilities capabilities =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);

    assertTrue(capabilities.supports(DialectFeature.SCHEMA_QUALIFIED_TABLES));
    assertFalse(capabilities.supports(DialectFeature.CATALOG_QUALIFIED_TABLES));
    assertThrows(UnsupportedOperationException.class, () -> capabilities.features().clear());
    assertEquals(
        capabilities,
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES));
  }

  @Test
  void renderedSqlDefensivelyCopiesParameters() {
    List<ParameterSlot<?>> parameters = new ArrayList<>();
    parameters.add(new ParameterSlot<>(0, Long.class, false));

    RenderedSql rendered = new RenderedSql("SELECT ?", parameters);
    parameters.clear();

    assertEquals(1, rendered.parameterCount());
    assertThrows(UnsupportedOperationException.class, () -> rendered.parameters().clear());
    assertThrows(IllegalArgumentException.class, () -> new RenderedSql(" ", List.of()));
  }
}
