package io.skis.metadata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies entity-metadata validation, lookup, and immutability contracts. */
class EntityMetaTest {

  @Test
  void createsSimpleEntityMetadataWithStablePropertyLookup() {
    PropertyMeta<Pet, Long> id = property(0, "id", Long.class, "id", false);
    PropertyMeta<Pet, String> name = property(1, "name", String.class, "pet_name", false);
    PrimaryKeyMeta<Pet> primaryKey = new PrimaryKeyMeta<>(List.of(id));

    EntityMeta<Pet> metadata =
        EntityMeta.simple(Pet.class, TableMeta.of("pet"), List.of(id, name), primaryKey, false);

    assertEquals(Pet.class, metadata.javaType());
    assertEquals("Pet", metadata.entityName());
    assertEquals(EntityMode.SIMPLE, metadata.mode());
    assertEquals(TableMeta.of("pet"), metadata.table());
    assertEquals(List.of(id, name), metadata.properties());
    assertSame(name, metadata.property("name"));
    assertSame(primaryKey, metadata.primaryKey().orElseThrow());
    assertTrue(metadata.version().isEmpty());
    assertFalse(metadata.readOnly());
  }

  @Test
  void defensivelyCopiesPropertiesAndPrimaryKeyParts() {
    PropertyMeta<Pet, Long> id = property(0, "id", Long.class, "id", false);
    List<PropertyMeta<Pet, ?>> properties = new ArrayList<>();
    properties.add(id);
    List<PropertyMeta<Pet, ?>> keyParts = new ArrayList<>();
    keyParts.add(id);

    PrimaryKeyMeta<Pet> primaryKey = new PrimaryKeyMeta<>(keyParts);
    EntityMeta<Pet> metadata =
        EntityMeta.simple(Pet.class, TableMeta.of("pet"), properties, primaryKey, false);
    properties.clear();
    keyParts.clear();

    assertEquals(List.of(id), metadata.properties());
    assertEquals(List.of(id), metadata.primaryKey().orElseThrow().properties());
    assertThrows(UnsupportedOperationException.class, () -> metadata.properties().clear());
  }

  @Test
  void permitsReadOnlyEntityWithoutPrimaryKey() {
    PropertyMeta<PetView, String> name = property(0, "name", String.class, "pet_name", true);

    EntityMeta<PetView> metadata =
        EntityMeta.simple(PetView.class, TableMeta.of("pet_view"), List.of(name), null, true);

    assertTrue(metadata.readOnly());
    assertTrue(metadata.primaryKey().isEmpty());
  }

  @Test
  void rejectsWritableEntityWithoutPrimaryKey() {
    PropertyMeta<Pet, String> name = property(0, "name", String.class, "pet_name", false);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> EntityMeta.simple(Pet.class, TableMeta.of("pet"), List.of(name), null, false));

    assertTrue(exception.getMessage().contains("requires a primary key"));
  }

  @Test
  void rejectsPrimaryKeyPropertyFromAnotherEntityMetadataSet() {
    PropertyMeta<Pet, Long> entityId = property(0, "id", Long.class, "id", false);
    PropertyMeta<Pet, Long> differentId = property(0, "id", Long.class, "pet_id", false);
    PrimaryKeyMeta<Pet> primaryKey = new PrimaryKeyMeta<>(List.of(differentId));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            EntityMeta.simple(
                Pet.class, TableMeta.of("pet"), List.of(entityId), primaryKey, false));
  }

  @Test
  void rejectsStructurallyEqualButNonCanonicalPrimaryKeyProperty() {
    PropertyMeta<Pet, Long> entityId = property(0, "id", Long.class, "id", false);
    PropertyMeta<Pet, Long> copiedId = property(0, "id", Long.class, "id", false);
    PrimaryKeyMeta<Pet> primaryKey = new PrimaryKeyMeta<>(List.of(copiedId));

    assertEquals(entityId, copiedId);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EntityMeta.simple(
                Pet.class, TableMeta.of("pet"), List.of(entityId), primaryKey, false));
  }

  @Test
  void rejectsNullablePrimaryKey() {
    PropertyMeta<Pet, Long> id = property(0, "id", Long.class, "id", true);

    assertThrows(IllegalArgumentException.class, () -> new PrimaryKeyMeta<>(List.of(id)));
  }

  @Test
  void rejectsDuplicateWritableColumnMappings() {
    PropertyMeta<Pet, Long> id = property(0, "id", Long.class, "id", false);
    PropertyMeta<Pet, String> duplicate = property(1, "externalId", String.class, "id", false);
    PrimaryKeyMeta<Pet> primaryKey = new PrimaryKeyMeta<>(List.of(id));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            EntityMeta.simple(
                Pet.class, TableMeta.of("pet"), List.of(id, duplicate), primaryKey, false));
  }

  @Test
  void identifiesCompositePrimaryKey() {
    PropertyMeta<Pet, Long> tenantId = property(0, "tenantId", Long.class, "tenant_id", false);
    PropertyMeta<Pet, Long> id = property(1, "id", Long.class, "id", false);

    PrimaryKeyMeta<Pet> primaryKey = new PrimaryKeyMeta<>(List.of(tenantId, id));

    assertTrue(primaryKey.composite());
  }

  @Test
  void modelsNumericVersionMetadata() {
    PropertyMeta<Pet, Long> id = property(0, "id", Long.class, "id", false);
    PropertyMeta<Pet, Long> version = property(1, "version", Long.class, "version", false);
    PrimaryKeyMeta<Pet> primaryKey = new PrimaryKeyMeta<>(List.of(id));
    VersionMeta<Pet, Long> versionMeta =
        new VersionMeta<>(version, VersionStrategy.NUMERIC_INCREMENT);

    EntityMeta<Pet> metadata =
        EntityMeta.simple(
            Pet.class, TableMeta.of("pet"), List.of(id, version), primaryKey, versionMeta, false);

    assertSame(versionMeta, metadata.version().orElseThrow());
    assertSame(version, metadata.version().orElseThrow().property());
    assertEquals(VersionStrategy.NUMERIC_INCREMENT, metadata.version().orElseThrow().strategy());
  }

  @Test
  void rejectsNullableVersionProperty() {
    PropertyMeta<Pet, Long> version = property(0, "version", Long.class, "version", true);

    assertThrows(
        IllegalArgumentException.class,
        () -> new VersionMeta<>(version, VersionStrategy.NUMERIC_INCREMENT));
  }

  @Test
  void rejectsVersionTypeUnsupportedByStrategy() {
    PropertyMeta<Pet, String> version = property(0, "version", String.class, "version", false);

    assertThrows(
        IllegalArgumentException.class,
        () -> new VersionMeta<>(version, VersionStrategy.NUMERIC_INCREMENT));
  }

  @Test
  void rejectsFloatingPointVersionTypes() {
    PropertyMeta<Pet, Float> floatVersion = property(0, "version", Float.class, "version", false);
    PropertyMeta<Pet, Double> doubleVersion =
        property(0, "version", Double.class, "version", false);

    assertThrows(
        IllegalArgumentException.class,
        () -> new VersionMeta<>(floatVersion, VersionStrategy.NUMERIC_INCREMENT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VersionMeta<>(doubleVersion, VersionStrategy.NUMERIC_INCREMENT));
  }

  @Test
  void acceptsExactNumericVersionTypes() {
    PropertyMeta<Pet, BigInteger> integerVersion =
        property(0, "version", BigInteger.class, "version", false);
    PropertyMeta<Pet, BigDecimal> decimalVersion =
        property(0, "version", BigDecimal.class, "version", false);

    assertDoesNotThrow(() -> new VersionMeta<>(integerVersion, VersionStrategy.NUMERIC_INCREMENT));
    assertDoesNotThrow(() -> new VersionMeta<>(decimalVersion, VersionStrategy.NUMERIC_INCREMENT));
  }

  @Test
  void validatesColumnRequirementsDeclaredByVersionStrategy() {
    PropertyMeta<Pet, Long> version =
        new PropertyMeta<>(
            0, "version", Long.class, new ColumnMeta("version", false, false, true, 0, 0, 0, ""));

    assertThrows(
        IllegalArgumentException.class,
        () -> new VersionMeta<>(version, VersionStrategy.NUMERIC_INCREMENT));
  }

  @Test
  void rejectsVersionPropertyOutsideEntityMetadata() {
    PropertyMeta<Pet, Long> id = property(0, "id", Long.class, "id", false);
    PropertyMeta<Pet, Long> entityVersion = property(1, "version", Long.class, "version", false);
    PropertyMeta<Pet, Long> differentVersion =
        property(1, "version", Long.class, "lock_version", false);
    PrimaryKeyMeta<Pet> primaryKey = new PrimaryKeyMeta<>(List.of(id));
    VersionMeta<Pet, Long> versionMeta =
        new VersionMeta<>(differentVersion, VersionStrategy.NUMERIC_INCREMENT);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            EntityMeta.simple(
                Pet.class,
                TableMeta.of("pet"),
                List.of(id, entityVersion),
                primaryKey,
                versionMeta,
                false));
  }

  @Test
  void rejectsStructurallyEqualButNonCanonicalVersionProperty() {
    PropertyMeta<Pet, Long> id = property(0, "id", Long.class, "id", false);
    PropertyMeta<Pet, Long> entityVersion = property(1, "version", Long.class, "version", false);
    PropertyMeta<Pet, Long> copiedVersion = property(1, "version", Long.class, "version", false);
    PrimaryKeyMeta<Pet> primaryKey = new PrimaryKeyMeta<>(List.of(id));
    VersionMeta<Pet, Long> versionMeta =
        new VersionMeta<>(copiedVersion, VersionStrategy.NUMERIC_INCREMENT);

    assertEquals(entityVersion, copiedVersion);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EntityMeta.simple(
                Pet.class,
                TableMeta.of("pet"),
                List.of(id, entityVersion),
                primaryKey,
                versionMeta,
                false));
  }

  @Test
  void rejectsVersionPropertyOnReadOnlyEntity() {
    PropertyMeta<PetView, Long> version = property(0, "version", Long.class, "version", false);
    VersionMeta<PetView, Long> versionMeta =
        new VersionMeta<>(version, VersionStrategy.NUMERIC_INCREMENT);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            EntityMeta.simple(
                PetView.class,
                TableMeta.of("pet_view"),
                List.of(version),
                null,
                versionMeta,
                true));
  }

  @Test
  void rejectsVersionPropertyInPrimaryKey() {
    PropertyMeta<Pet, Long> version = property(0, "version", Long.class, "version", false);
    PrimaryKeyMeta<Pet> primaryKey = new PrimaryKeyMeta<>(List.of(version));
    VersionMeta<Pet, Long> versionMeta =
        new VersionMeta<>(version, VersionStrategy.NUMERIC_INCREMENT);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            EntityMeta.simple(
                Pet.class, TableMeta.of("pet"), List.of(version), primaryKey, versionMeta, false));
  }

  private static <E, V> PropertyMeta<E, V> property(
      int ordinal, String propertyName, Class<V> javaType, String columnName, boolean nullable) {
    return new PropertyMeta<>(ordinal, propertyName, javaType, ColumnMeta.of(columnName, nullable));
  }

  /** Test entity used by writable metadata scenarios. */
  private record Pet(Long id, String name) {}

  /** Read-only projection used by metadata validation tests. */
  private record PetView(String name) {}
}
