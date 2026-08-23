package io.skis.sql.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IdentifierTest {

  @Test
  void acceptsPortableSqlIdentifiers() {
    assertEquals("pet", Identifier.of("pet").value());
    assertEquals("pet_2", Identifier.of("pet_2").value());
    assertEquals("_temporary", Identifier.of("_temporary").value());
    assertEquals("PET", Identifier.of("PET").value());
  }

  @Test
  void rejectsEmptyAndNonPortableIdentifiers() {
    assertThrows(NullPointerException.class, () -> Identifier.of(null));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of(""));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of(" "));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of("2pet"));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of("pet-name"));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of("pet name"));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of("b;delete"));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of("\"pet\""));
  }
}
