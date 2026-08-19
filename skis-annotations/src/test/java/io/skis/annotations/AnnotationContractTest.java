package io.skis.annotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;

/** Verifies the runtime contracts of SKIS mapping annotations. */
class AnnotationContractTest {

  @Test
  void retainsEntityAndTableDeclarations() {
    SkisEntity entity = Book.class.getAnnotation(SkisEntity.class);
    Table table = Book.class.getAnnotation(Table.class);

    assertFalse(entity.readOnly());
    assertEquals("book", table.name());
    assertEquals("inventory", table.schema());
  }

  @Test
  void supportsRecordComponentMappings() {
    RecordComponent id = Book.class.getRecordComponents()[0];
    RecordComponent displayName = Book.class.getRecordComponents()[1];
    RecordComponent version = Book.class.getRecordComponents()[2];

    assertTrue(id.isAnnotationPresent(Id.class));
    assertEquals("book_id", id.getAnnotation(Column.class).name());
    assertTrue(displayName.isAnnotationPresent(Transient.class));
    assertTrue(version.isAnnotationPresent(Version.class));
  }

  /** Test entity used to verify record-component annotation mappings. */
  @SkisEntity
  @Table(name = "book", schema = "inventory")
  private record Book(
      @Id @Column(name = "book_id", nullable = false) Long id,
      @Transient String displayName,
      @Version @Column(nullable = false) long version) {}
}
