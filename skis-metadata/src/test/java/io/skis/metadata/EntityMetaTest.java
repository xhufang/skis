package io.skis.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies entity-metadata validation, lookup, and immutability contracts. */
class EntityMetaTest {

  @Test
  void createsSimpleEntityMetadataWithStablePropertyLookup() {
    PropertyMeta<Book, Long> id = property(0, "id", Long.class, "id", false);
    PropertyMeta<Book, String> name = property(1, "name", String.class, "book_name", false);
    PrimaryKeyMeta<Book> primaryKey = new PrimaryKeyMeta<>(List.of(id));

    EntityMeta<Book> metadata =
        EntityMeta.simple(Book.class, TableMeta.of("book"), List.of(id, name), primaryKey, false);

    assertEquals(Book.class, metadata.javaType());
    assertEquals("Book", metadata.entityName());
    assertEquals(EntityMode.SIMPLE, metadata.mode());
    assertEquals(TableMeta.of("book"), metadata.table());
    assertEquals(List.of(id, name), metadata.properties());
    assertSame(name, metadata.property("name"));
    assertSame(primaryKey, metadata.primaryKey().orElseThrow());
    assertTrue(metadata.version().isEmpty());
    assertFalse(metadata.readOnly());
  }

  @Test
  void defensivelyCopiesPropertiesAndPrimaryKeyParts() {
    PropertyMeta<Book, Long> id = property(0, "id", Long.class, "id", false);
    List<PropertyMeta<Book, ?>> properties = new ArrayList<>();
    properties.add(id);
    List<PropertyMeta<Book, ?>> keyParts = new ArrayList<>();
    keyParts.add(id);

    PrimaryKeyMeta<Book> primaryKey = new PrimaryKeyMeta<>(keyParts);
    EntityMeta<Book> metadata =
        EntityMeta.simple(Book.class, TableMeta.of("book"), properties, primaryKey, false);
    properties.clear();
    keyParts.clear();

    assertEquals(List.of(id), metadata.properties());
    assertEquals(List.of(id), metadata.primaryKey().orElseThrow().properties());
    assertThrows(UnsupportedOperationException.class, () -> metadata.properties().clear());
  }

  @Test
  void permitsReadOnlyEntityWithoutPrimaryKey() {
    PropertyMeta<BookView, String> name = property(0, "name", String.class, "book_name", true);

    EntityMeta<BookView> metadata =
        EntityMeta.simple(BookView.class, TableMeta.of("book_view"), List.of(name), null, true);

    assertTrue(metadata.readOnly());
    assertTrue(metadata.primaryKey().isEmpty());
  }

  @Test
  void rejectsWritableEntityWithoutPrimaryKey() {
    PropertyMeta<Book, String> name = property(0, "name", String.class, "book_name", false);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> EntityMeta.simple(Book.class, TableMeta.of("book"), List.of(name), null, false));

    assertTrue(exception.getMessage().contains("requires a primary key"));
  }

  @Test
  void rejectsPrimaryKeyPropertyFromAnotherEntityMetadataSet() {
    PropertyMeta<Book, Long> entityId = property(0, "id", Long.class, "id", false);
    PropertyMeta<Book, Long> differentId = property(0, "id", Long.class, "book_id", false);
    PrimaryKeyMeta<Book> primaryKey = new PrimaryKeyMeta<>(List.of(differentId));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            EntityMeta.simple(
                Book.class, TableMeta.of("book"), List.of(entityId), primaryKey, false));
  }

  @Test
  void rejectsNullablePrimaryKey() {
    PropertyMeta<Book, Long> id = property(0, "id", Long.class, "id", true);

    assertThrows(IllegalArgumentException.class, () -> new PrimaryKeyMeta<>(List.of(id)));
  }

  @Test
  void rejectsDuplicateWritableColumnMappings() {
    PropertyMeta<Book, Long> id = property(0, "id", Long.class, "id", false);
    PropertyMeta<Book, String> duplicate = property(1, "externalId", String.class, "id", false);
    PrimaryKeyMeta<Book> primaryKey = new PrimaryKeyMeta<>(List.of(id));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            EntityMeta.simple(
                Book.class, TableMeta.of("book"), List.of(id, duplicate), primaryKey, false));
  }

  @Test
  void identifiesCompositePrimaryKey() {
    PropertyMeta<Book, Long> tenantId = property(0, "tenantId", Long.class, "tenant_id", false);
    PropertyMeta<Book, Long> id = property(1, "id", Long.class, "id", false);

    PrimaryKeyMeta<Book> primaryKey = new PrimaryKeyMeta<>(List.of(tenantId, id));

    assertTrue(primaryKey.composite());
  }

  @Test
  void modelsNumericVersionMetadata() {
    PropertyMeta<Book, Long> id = property(0, "id", Long.class, "id", false);
    PropertyMeta<Book, Long> version = property(1, "version", Long.class, "version", false);
    PrimaryKeyMeta<Book> primaryKey = new PrimaryKeyMeta<>(List.of(id));
    VersionMeta<Book, Long> versionMeta =
        new VersionMeta<>(version, VersionStrategy.NUMERIC_INCREMENT);

    EntityMeta<Book> metadata =
        EntityMeta.simple(
            Book.class, TableMeta.of("book"), List.of(id, version), primaryKey, versionMeta, false);

    assertSame(versionMeta, metadata.version().orElseThrow());
    assertSame(version, metadata.version().orElseThrow().property());
    assertEquals(VersionStrategy.NUMERIC_INCREMENT, metadata.version().orElseThrow().strategy());
  }

  @Test
  void rejectsNullableVersionProperty() {
    PropertyMeta<Book, Long> version = property(0, "version", Long.class, "version", true);

    assertThrows(
        IllegalArgumentException.class,
        () -> new VersionMeta<>(version, VersionStrategy.NUMERIC_INCREMENT));
  }

  @Test
  void rejectsVersionTypeUnsupportedByStrategy() {
    PropertyMeta<Book, String> version = property(0, "version", String.class, "version", false);

    assertThrows(
        IllegalArgumentException.class,
        () -> new VersionMeta<>(version, VersionStrategy.NUMERIC_INCREMENT));
  }

  @Test
  void validatesColumnRequirementsDeclaredByVersionStrategy() {
    PropertyMeta<Book, Long> version =
        new PropertyMeta<>(
            0, "version", Long.class, new ColumnMeta("version", false, false, true, 0, 0, 0, ""));

    assertThrows(
        IllegalArgumentException.class,
        () -> new VersionMeta<>(version, VersionStrategy.NUMERIC_INCREMENT));
  }

  @Test
  void rejectsVersionPropertyOutsideEntityMetadata() {
    PropertyMeta<Book, Long> id = property(0, "id", Long.class, "id", false);
    PropertyMeta<Book, Long> entityVersion = property(1, "version", Long.class, "version", false);
    PropertyMeta<Book, Long> differentVersion =
        property(1, "version", Long.class, "lock_version", false);
    PrimaryKeyMeta<Book> primaryKey = new PrimaryKeyMeta<>(List.of(id));
    VersionMeta<Book, Long> versionMeta =
        new VersionMeta<>(differentVersion, VersionStrategy.NUMERIC_INCREMENT);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            EntityMeta.simple(
                Book.class,
                TableMeta.of("book"),
                List.of(id, entityVersion),
                primaryKey,
                versionMeta,
                false));
  }

  @Test
  void rejectsVersionPropertyOnReadOnlyEntity() {
    PropertyMeta<BookView, Long> version = property(0, "version", Long.class, "version", false);
    VersionMeta<BookView, Long> versionMeta =
        new VersionMeta<>(version, VersionStrategy.NUMERIC_INCREMENT);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            EntityMeta.simple(
                BookView.class,
                TableMeta.of("book_view"),
                List.of(version),
                null,
                versionMeta,
                true));
  }

  @Test
  void rejectsVersionPropertyInPrimaryKey() {
    PropertyMeta<Book, Long> version = property(0, "version", Long.class, "version", false);
    PrimaryKeyMeta<Book> primaryKey = new PrimaryKeyMeta<>(List.of(version));
    VersionMeta<Book, Long> versionMeta =
        new VersionMeta<>(version, VersionStrategy.NUMERIC_INCREMENT);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            EntityMeta.simple(
                Book.class,
                TableMeta.of("book"),
                List.of(version),
                primaryKey,
                versionMeta,
                false));
  }

  private static <E, V> PropertyMeta<E, V> property(
      int ordinal, String propertyName, Class<V> javaType, String columnName, boolean nullable) {
    return new PropertyMeta<>(ordinal, propertyName, javaType, ColumnMeta.of(columnName, nullable));
  }

  /** Test entity used by writable metadata scenarios. */
  private record Book(Long id, String name) {}

  /** Read-only projection used by metadata validation tests. */
  private record BookView(String name) {}
}
