package io.skis.dialect;

/** Independently testable SQL features exposed by the initial dialect contract. */
public enum DialectFeature {
  /** Tables may be qualified with a schema name. */
  SCHEMA_QUALIFIED_TABLES,

  /** Tables may be qualified with a catalog name in generated SQL. */
  CATALOG_QUALIFIED_TABLES
}
