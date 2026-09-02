package io.skis.dialect;

/** Independently testable SQL features exposed by the initial dialect contract. */
public enum DialectFeature {
  /** Tables may be qualified with a schema name. */
  SCHEMA_QUALIFIED_TABLES,

  /** Tables may be qualified with a catalog name in generated SQL. */
  CATALOG_QUALIFIED_TABLES,

  /** SELECT supports a parameterized LIMIT clause, including fetch-first queries. */
  PARAMETERIZED_LIMIT,

  /** SELECT supports a parameterized OFFSET clause. */
  PARAMETERIZED_OFFSET,

  /** ORDER BY supports native NULLS FIRST/NULLS LAST syntax. */
  NULLS_FIRST_LAST,

  /** COUNT(DISTINCT expression) is supported. */
  COUNT_DISTINCT
}
