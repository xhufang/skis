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
    SkisEntity entity = Pet.class.getAnnotation(SkisEntity.class);
    Table table = Pet.class.getAnnotation(Table.class);

    assertFalse(entity.readOnly());
    assertEquals("pet", table.name());
    assertEquals("shelter", table.schema());
  }

  @Test
  void supportsRecordComponentMappings() {
    RecordComponent id = Pet.class.getRecordComponents()[0];
    RecordComponent displayName = Pet.class.getRecordComponents()[1];
    RecordComponent version = Pet.class.getRecordComponents()[2];

    assertTrue(id.isAnnotationPresent(Id.class));
    assertEquals("pet_id", id.getAnnotation(Column.class).name());
    assertTrue(displayName.isAnnotationPresent(Transient.class));
    assertTrue(version.isAnnotationPresent(Version.class));
  }

  /** Test entity used to verify record-component annotation mappings. */
  @SkisEntity
  @Table(name = "pet", schema = "shelter")
  private record Pet(
      @Id @Column(name = "pet_id", nullable = false) Long id,
      @Transient String displayName,
      @Version @Column(nullable = false) long version) {}
}
