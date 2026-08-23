package io.skis.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies value-object metadata validation and factory defaults. */
class ValueMetadataTest {

  @Test
  void representsQualifiedAndUnqualifiedTables() {
    TableMeta qualified = new TableMeta("catalog", "shelter", "pet");
    TableMeta unqualified = TableMeta.of("pet");

    assertTrue(qualified.hasCatalog());
    assertTrue(qualified.hasSchema());
    assertFalse(unqualified.hasCatalog());
    assertFalse(unqualified.hasSchema());
  }

  @Test
  void rejectsBlankPhysicalNames() {
    assertThrows(IllegalArgumentException.class, () -> TableMeta.of(" "));
    assertThrows(IllegalArgumentException.class, () -> ColumnMeta.of(" ", true));
  }

  @Test
  void createsDefaultColumnMetadata() {
    ColumnMeta column = ColumnMeta.of("pet_name", false);

    assertEquals("pet_name", column.name());
    assertFalse(column.nullable());
    assertTrue(column.insertable());
    assertTrue(column.updatable());
    assertEquals(255, column.length());
    assertEquals(0, column.precision());
    assertEquals(0, column.scale());
    assertEquals("", column.comment());
  }

  @Test
  void rejectsScaleGreaterThanPrecision() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ColumnMeta("price", false, true, true, 0, 2, 3, ""));
  }
}
