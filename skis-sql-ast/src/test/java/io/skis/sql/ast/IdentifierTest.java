package io.skis.sql.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IdentifierTest {

  @Test
  void acceptsPortableSqlIdentifiers() {
    assertEquals("book", Identifier.of("book").value());
    assertEquals("book_2", Identifier.of("book_2").value());
    assertEquals("_temporary", Identifier.of("_temporary").value());
    assertEquals("BOOK", Identifier.of("BOOK").value());
  }

  @Test
  void rejectsEmptyAndNonPortableIdentifiers() {
    assertThrows(NullPointerException.class, () -> Identifier.of(null));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of(""));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of(" "));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of("2book"));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of("book-name"));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of("book name"));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of("b;delete"));
    assertThrows(IllegalArgumentException.class, () -> Identifier.of("\"book\""));
  }
}
