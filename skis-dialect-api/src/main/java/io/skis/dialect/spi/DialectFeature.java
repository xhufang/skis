package io.skis.dialect.spi;

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
  COUNT_DISTINCT,

  /** SELECT supports INNER JOIN with an ON predicate. */
  INNER_JOIN,

  /** SELECT supports LEFT JOIN with an ON predicate. */
  LEFT_JOIN,

  /** SELECT supports RIGHT JOIN with an ON predicate. */
  RIGHT_JOIN,

  /** SELECT supports FULL JOIN with an ON predicate. */
  FULL_JOIN,

  /** SELECT supports CROSS JOIN without an ON predicate. */
  CROSS_JOIN,

  /** Predicates may embed a SELECT through EXISTS or NOT EXISTS. */
  EXISTS_SUBQUERY,

  /** Predicates may compare one value with a one-column SELECT through IN or NOT IN. */
  IN_SUBQUERY,

  /** Value-expression positions may embed a one-column SELECT as a scalar subquery. */
  SCALAR_SUBQUERY,

  /** A nested SELECT may reference a source visible in an ancestor query block. */
  CORRELATED_SUBQUERY,

  /** FROM and JOIN may contain an explicitly aliased SELECT-derived relation. */
  DERIVED_TABLE,

  /** SELECT supports explicit grouping of supported scalar SQL values. */
  GROUP_BY,

  /** SELECT supports a separate HAVING predicate. */
  HAVING,

  /**
   * COUNT(*) and COUNT(expression) are supported. COUNT(DISTINCT expression) additionally requires
   * {@link #COUNT_DISTINCT}.
   */
  COUNT_AGGREGATE,

  /** SUM(expression) is supported for numeric inputs under the aggregate type rules. */
  SUM_AGGREGATE,

  /** AVG(expression) is supported for numeric inputs under the aggregate type rules. */
  AVG_AGGREGATE,

  /** MIN(expression) is supported for orderable inputs under the aggregate type rules. */
  MIN_AGGREGATE,

  /** MAX(expression) is supported for orderable inputs under the aggregate type rules. */
  MAX_AGGREGATE,

  /** The driver can return an explicitly requested primary-key column for a single INSERT. */
  JDBC_GENERATED_KEYS,

  /** INSERT supports the explicit DEFAULT VALUES form with no bound columns. */
  INSERT_DEFAULT_VALUES,

  /** INSERT, UPDATE and DELETE can return explicitly selected target columns. */
  MUTATION_RETURNING,

  /** Mutation results use FINAL TABLE / OLD TABLE instead of native RETURNING. */
  MUTATION_RESULT_TABLE,

  /** Explicit-column PostgreSQL conflict actions. */
  INSERT_ON_CONFLICT,

  /** Single-row VALUES source with MERGE branches. */
  MERGE_USING,

  /** H2 KEY grammar, independently validated from USING. */
  MERGE_KEY
}
