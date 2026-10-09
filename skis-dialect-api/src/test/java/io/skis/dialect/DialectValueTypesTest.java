package io.skis.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.sql.ast.ParameterSlot;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
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
    assertEquals(capabilities, DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES));
  }

  @Test
  void capabilityVersionsAreDeterministicAndFeatureSensitive() {
    DialectCapabilities first =
        DialectCapabilities.of(
            DialectFeature.SCHEMA_QUALIFIED_TABLES, DialectFeature.PARAMETERIZED_LIMIT);
    DialectCapabilities reordered =
        DialectCapabilities.of(
            DialectFeature.PARAMETERIZED_LIMIT, DialectFeature.SCHEMA_QUALIFIED_TABLES);
    DialectCapabilities different =
        DialectCapabilities.of(
            DialectFeature.SCHEMA_QUALIFIED_TABLES, DialectFeature.PARAMETERIZED_OFFSET);

    assertEquals(first.version(), reordered.version());
    assertNotEquals(first.version(), different.version());
    assertEquals(DialectCapabilities.none().version(), DialectCapabilities.none().version());
  }

  @Test
  void customDialectUsesCompatibleDefaults() {
    DialectCapabilities capabilities =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    Dialect dialect =
        new Dialect() {
          @Override
          public String id() {
            return "custom";
          }

          @Override
          public IdentifierRules identifierRules() {
            return identifier -> identifier;
          }

          @Override
          public DialectCapabilities capabilities() {
            return capabilities;
          }

          @Override
          public SqlRenderer renderer() {
            return statement -> {
              throw new AssertionError("renderer must not be used by dialect identity defaults");
            };
          }
        };

    assertEquals(capabilities.version(), dialect.capabilityVersion());
    assertFalse(dialect.hasStablePlanCacheIdentity());
    assertFalse(dialect.supportsResolvedQueryValidation());
    assertSame(StatementParameterLimit.unknown(), dialect.maxStatementParameters());
  }

  @Test
  void statementParameterLimitsKeepAllThreeStatesDistinct() {
    StatementParameterLimit unknown = StatementParameterLimit.unknown();
    StatementParameterLimit unbounded = StatementParameterLimit.unbounded();
    StatementParameterLimit explicit = StatementParameterLimit.explicit(65_535);
    StatementParameterLimit maximumInteger =
        StatementParameterLimit.explicit(Integer.MAX_VALUE);

    assertSame(unknown, StatementParameterLimit.unknown());
    assertSame(unbounded, StatementParameterLimit.unbounded());
    assertEquals(StatementParameterLimit.Kind.UNKNOWN, unknown.kind());
    assertEquals(StatementParameterLimit.Kind.UNBOUNDED, unbounded.kind());
    assertEquals(StatementParameterLimit.Kind.EXPLICIT, explicit.kind());
    assertEquals(OptionalInt.empty(), unknown.maximum());
    assertEquals(OptionalInt.empty(), unbounded.maximum());
    assertEquals(OptionalInt.of(65_535), explicit.maximum());
    assertEquals(StatementParameterLimit.Kind.EXPLICIT, maximumInteger.kind());
    assertEquals(OptionalInt.of(Integer.MAX_VALUE), maximumInteger.maximum());
    assertNotEquals(unknown, unbounded);
    assertNotEquals(unbounded, explicit);
    assertNotEquals(unbounded, maximumInteger);
    assertEquals(explicit, StatementParameterLimit.explicit(65_535));
    assertThrows(IllegalArgumentException.class, () -> StatementParameterLimit.explicit(0));
    assertThrows(IllegalArgumentException.class, () -> StatementParameterLimit.explicit(-1));
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
