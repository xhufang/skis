package io.skis.metadata;

import java.util.Objects;

/** Immutable physical table identity used by generated entity metadata. */
public record TableMeta(String catalog, String schema, String name) {

  /**
   * Creates table metadata.
   *
   * @param catalog physical catalog name, or an empty string when unspecified
   * @param schema physical schema name, or an empty string when unspecified
   * @param name physical table name
   */
  public TableMeta {
    catalog = requireOptionalIdentifier(catalog, "catalog");
    schema = requireOptionalIdentifier(schema, "schema");
    name = requireIdentifier(name, "table name");
  }

  /** Creates an unqualified table identity. */
  public static TableMeta of(String name) {
    return new TableMeta("", "", name);
  }

  /** Returns whether a catalog qualifier is present. */
  public boolean hasCatalog() {
    return !catalog.isEmpty();
  }

  /** Returns whether a schema qualifier is present. */
  public boolean hasSchema() {
    return !schema.isEmpty();
  }

  private static String requireOptionalIdentifier(String value, String label) {
    Objects.requireNonNull(value, label);
    if (!value.isEmpty() && value.isBlank()) {
      throw new IllegalArgumentException(label + " must not contain only whitespace");
    }
    return value;
  }

  private static String requireIdentifier(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
