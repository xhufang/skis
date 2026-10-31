package io.skis.mutation.internal;

import static org.junit.jupiter.api.Assertions.*;
import io.skis.core.NonUniqueResultException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

class WrittenTest {
  @Test void distinguishesNoRowFromOneNullScalar() {
    var absent = new DefaultWritten<@Nullable String>(0, null);
    var presentNull = new DefaultWritten<@Nullable String>(1, null);
    assertFalse(absent.one().hasRow());
    assertTrue(presentNull.one().hasRow());
    assertTrue(presentNull.one().isNull());
    assertEquals(Collections.singletonList(null), presentNull.rows());
    assertEquals(0, absent.affectedRows());
    assertEquals(1, presentNull.affectedRows());
  }
  @Test void reusesImmutableOwnedRowsAndRejectsNonUniqueOne() {
    List<String> source = List.of("first", "second");
    var result = DefaultWritten.ownedRows(source.size(), source);
    assertSame(source, result.rows());
    assertEquals(List.of("first", "second"), result.rows());
    assertEquals(2, result.affectedRows());
    assertThrows(NonUniqueResultException.class, result::one);
    assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
  }
  @Test void compactSingleAndRedactedFormatting() {
    Object value = new Object() { @Override public String toString() { throw new AssertionError("sensitive value"); } };
    var result = new DefaultWritten<>(1, value);
    assertSame(value, result.one().get());
    assertSame(value, result.rows().getFirst());
    assertEquals("Written[values redacted]", result.toString());
    assertThrows(IllegalArgumentException.class, () -> new DefaultWritten<>(-1, value));
  }
}
