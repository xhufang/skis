package io.skis.dialect.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.dialect.spi.IdentifierRules;
import org.junit.jupiter.api.Test;

class StandardIdentifierRulesTest {

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
}
