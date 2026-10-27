package io.skis.core;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Complete runtime error-code table.
 *
 * <p>Each member owns a stable, explicit string code. Java callers switch on the enum; logs and
 * documentation use {@link #code()}. Codes are grouped by {@link SkisErrorFamily}. The same reason
 * reuses one code even when detected at different query blocks, statements, or lifecycle phases.
 *
 * <p>The database category name is deliberately stored as a string so that {@code skis-core} does
 * not depend on the dialect SPI. {@code SqlExceptionCategory} owns the typed mapping back to these
 * codes.
 */
public enum SkisErrorCode {
  // SKISA: assembly, configuration, and metadata.
  ASSEMBLY_MODEL_MISSING(
      "SKISA001",
      SkisErrorFamily.ASSEMBLY,
      SkisDiagnosticStage.ASSEMBLY,
      "runtime model is not registered for the requested entity"),
  ASSEMBLY_MODEL_DUPLICATE(
      "SKISA002",
      SkisErrorFamily.ASSEMBLY,
      SkisDiagnosticStage.ASSEMBLY,
      "runtime model or mutation plan set is registered more than once"),
  ASSEMBLY_INDEX_INVALID(
      "SKISA003",
      SkisErrorFamily.ASSEMBLY,
      SkisDiagnosticStage.ASSEMBLY,
      "generated provider index is missing or malformed"),
  ASSEMBLY_ABI_INCOMPATIBLE(
      "SKISA004",
      SkisErrorFamily.ASSEMBLY,
      SkisDiagnosticStage.ASSEMBLY,
      "generated code is not compatible with the runtime ABI"),
  ASSEMBLY_PROVIDER_INVALID(
      "SKISA005",
      SkisErrorFamily.ASSEMBLY,
      SkisDiagnosticStage.ASSEMBLY,
      "generated provider cannot be loaded or initialized"),
  ASSEMBLY_CONFIGURATION_INVALID(
      "SKISA006",
      SkisErrorFamily.ASSEMBLY,
      SkisDiagnosticStage.CONFIGURATION,
      "required runtime configuration is missing or invalid"),
  CACHE_CONFIGURATION_INVALID(
      "SKISA007",
      SkisErrorFamily.ASSEMBLY,
      SkisDiagnosticStage.CACHE_CONFIGURATION,
      "plan cache configuration is invalid"),
  METADATA_INVALID(
      "SKISA008",
      SkisErrorFamily.ASSEMBLY,
      SkisDiagnosticStage.METADATA,
      "entity metadata violates a structural contract"),
  METADATA_PRIMARY_KEY_REQUIRED(
      "SKISA009",
      SkisErrorFamily.ASSEMBLY,
      SkisDiagnosticStage.METADATA,
      "writable entity metadata requires a primary key"),
  METADATA_VERSION_INVALID(
      "SKISA010",
      SkisErrorFamily.ASSEMBLY,
      SkisDiagnosticStage.METADATA,
      "optimistic-version metadata is invalid"),
  MUTATION_BINDERS_MISSING(
      "SKISA011",
      SkisErrorFamily.ASSEMBLY,
      SkisDiagnosticStage.ASSEMBLY,
      "generated mutation bindings are missing or incompatible"),

  // SKISQ: query construction, semantics, result shape, and non-null mapping.
  QUERY_SOURCE_OUT_OF_SCOPE(
      "SKISQ001",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "query source is not visible in the final query scope"),
  QUERY_WHERE_CONTRACT(
      "SKISQ002",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.QUERY_CONSTRUCTION,
      "WHERE construction violates its single-predicate contract"),
  QUERY_ORDER_BY_CONTRACT(
      "SKISQ003",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.QUERY_CONSTRUCTION,
      "ORDER BY construction is empty, repeated, or inconsistent"),
  QUERY_ORDER_BY_NOT_SELECTED(
      "SKISQ004",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "ORDER BY expression is not part of the selected DISTINCT result"),
  QUERY_JOIN_CONTRACT(
      "SKISQ005",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.QUERY_CONSTRUCTION,
      "JOIN construction violates the selected JOIN contract"),
  QUERY_SELECTION_SHAPE(
      "SKISQ006",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "selected expressions and their resolved result shape do not match"),
  QUERY_DERIVED_SHAPE(
      "SKISQ007",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "derived output or derived relation shape is invalid"),
  QUERY_TYPE_MISMATCH(
      "SKISQ008",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "expression or parameter Java/SQL type does not match the query contract"),
  QUERY_NULLABILITY_CONTRACT(
      "SKISQ009",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "declared nullability does not match the effective query expression"),
  QUERY_PARAMETER_MISSING(
      "SKISQ010",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.PARAMETER_BINDING,
      "a declared query parameter has no binding"),
  QUERY_PARAMETER_DECLARATION(
      "SKISQ011",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.PARAMETER_BINDING,
      "a query parameter binding is undeclared or duplicated"),
  QUERY_PARAMETER_VALUE(
      "SKISQ012",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.PARAMETER_BINDING,
      "a query parameter value violates its declared type or nullability"),
  QUERY_PARAMETER_LAYOUT(
      "SKISQ013",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.COMPILATION,
      "logical or JDBC parameter layout does not match the compiled statement"),
  QUERY_PROJECTION_MAPPING(
      "SKISQ014",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.RESULT_SHAPE,
      "generated projection mapping does not match its selected parameters"),
  QUERY_RESULT_MAPPING(
      "SKISQ015",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.RESULT_SHAPE,
      "resolved value mapping or generated decoder is inconsistent"),
  QUERY_NON_NULL_RESULT(
      "SKISQ016",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.RESULT_DECODING,
      "a non-null query result decoded SQL NULL"),
  QUERY_MULTIPLE_ROWS(
      "SKISQ017",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.RESULT_DECODING,
      "a query requiring at most one row observed more than one"),
  QUERY_PAGINATION_ORDER_REQUIRED(
      "SKISQ018",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.PAGINATION,
      "pagination requires an explicit stable ORDER BY"),
  QUERY_PAGINATION_UNSTABLE_ORDER(
      "SKISQ019",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.PAGINATION,
      "pagination ordering is not stable for a table source"),
  QUERY_PAGINATION_UNSUPPORTED(
      "SKISQ020",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.PAGINATION,
      "pagination shape is not supported for this query"),
  QUERY_PAGINATION_OVERFLOW(
      "SKISQ021",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.PAGINATION,
      "page size or slice offset exceeds the supported numeric range"),
  QUERY_CONTINUATION_INVALID(
      "SKISQ022",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.CONTINUATION,
      "slice continuation format, ABI, order, or parameter digest does not match"),
  QUERY_CONTINUATION_VALUE(
      "SKISQ023",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.CONTINUATION,
      "slice continuation ordering value is missing, null, or of the wrong type"),
  QUERY_COUNT_CONTRACT(
      "SKISQ024",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "explicit or automatic count cannot preserve the query semantics"),
  QUERY_UNSUPPORTED_STRUCTURE(
      "SKISQ025",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.QUERY_CONSTRUCTION,
      "query structure is not supported by the current portable contract"),
  QUERY_CAPTURED_VALUE(
      "SKISQ026",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.QUERY_CONSTRUCTION,
      "reusable query description captured an ordinary value"),
  QUERY_CURSOR_THREAD(
      "SKISQ027",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.USAGE,
      "cursor or stream was used outside its resource-owning thread"),
  QUERY_EXPRESSION_IDENTITY(
      "SKISQ028",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "expression or table occurrence identity does not belong to this query"),
  QUERY_COMPILED_SHAPE(
      "SKISQ029",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.COMPILATION,
      "compiled structure or generated mapping count does not match the description"),
  QUERY_METADATA_REQUIRED(
      "SKISQ030",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "query operation requires primary-key or version metadata that is absent"),
  QUERY_GROUPING_CONTRACT(
      "SKISQ031",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "grouping keys or grouped expressions violate the query semantics"),
  QUERY_AGGREGATE_CONTRACT(
      "SKISQ032",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "aggregate clause, nesting, or query-block ownership is invalid"),

  QUERY_GROUP_BY_CONTRACT(
      "SKISQ033",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.QUERY_CONSTRUCTION,
      "GROUP BY construction is empty, null, or repeated"),
  QUERY_HAVING_CONTRACT(
      "SKISQ034",
      SkisErrorFamily.QUERY,
      SkisDiagnosticStage.QUERY_CONSTRUCTION,
      "HAVING construction is null, repeated, or lacks an initial predicate"),

  // SKISD: dialect capability, rendering, lowering, and structural conversion.
  DIALECT_FEATURE_UNSUPPORTED(
      "SKISD001",
      SkisErrorFamily.DIALECT,
      SkisDiagnosticStage.DIALECT_VALIDATION,
      "dialect does not support the required capability"),
  DIALECT_RESERVED_STRUCTURE(
      "SKISD002",
      SkisErrorFamily.DIALECT,
      SkisDiagnosticStage.DIALECT_VALIDATION,
      "dialect cannot render reserved structure before its capability is enabled"),
  DIALECT_INVALID_STRUCTURE(
      "SKISD003",
      SkisErrorFamily.DIALECT,
      SkisDiagnosticStage.DIALECT_VALIDATION,
      "structure cannot be rendered by the selected dialect"),
  DIALECT_NULLS_ORDERING_UNSUPPORTED(
      "SKISD004",
      SkisErrorFamily.DIALECT,
      SkisDiagnosticStage.DIALECT_VALIDATION,
      "dialect requires native NULLS FIRST/LAST for this ordering"),
  DIALECT_PARAMETER_SHAPE(
      "SKISD005",
      SkisErrorFamily.DIALECT,
      SkisDiagnosticStage.RENDERING,
      "dialect rendered a parameter shape that does not match the AST"),
  DIALECT_RENDER_FAILURE(
      "SKISD006",
      SkisErrorFamily.DIALECT,
      SkisDiagnosticStage.RENDERING,
      "dialect rendering failed"),

  // SKISX: JDBC, database errors, transactions, and resource lifecycle.
  JDBC_CONNECTION_ACQUIRE_FAILED(
      "SKISX001",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.RESOURCE_ACQUIRE,
      "JDBC connection could not be acquired"),
  JDBC_STATEMENT_CONFIGURE_FAILED(
      "SKISX002",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.EXECUTION,
      "JDBC statement could not be configured"),
  JDBC_RESULT_SET_CLOSE_FAILED(
      "SKISX004",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.RESOURCE_CLOSE,
      "JDBC result set could not be closed"),
  JDBC_STATEMENT_CLOSE_FAILED(
      "SKISX005",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.RESOURCE_CLOSE,
      "JDBC statement could not be closed"),
  JDBC_CONNECTION_RELEASE_FAILED(
      "SKISX006",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.RESOURCE_CLOSE,
      "JDBC connection could not be released"),
  JDBC_PARAMETER_BINDING_FAILED(
      "SKISX007",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.EXECUTION,
      "compiled JDBC parameter binder violated its parameter contract"),
  DB_DUPLICATE_KEY(
      "SKISX010",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.EXECUTION,
      "a unique or primary key was duplicated",
      "DUPLICATE_KEY"),
  DB_FOREIGN_KEY_VIOLATION(
      "SKISX011",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.EXECUTION,
      "a foreign-key relationship was violated",
      "FOREIGN_KEY_VIOLATION"),
  DB_CONSTRAINT_VIOLATION(
      "SKISX012",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.EXECUTION,
      "an integrity constraint was violated",
      "CONSTRAINT_VIOLATION"),
  DB_CONNECTION_FAILURE(
      "SKISX013",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.EXECUTION,
      "the database connection failed or became unusable",
      "CONNECTION_FAILURE"),
  DB_TIMEOUT(
      "SKISX014",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.EXECUTION,
      "statement execution exceeded a database or JDBC timeout",
      "TIMEOUT"),
  DB_QUERY_CANCELED(
      "SKISX015",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.EXECUTION,
      "statement execution was canceled",
      "QUERY_CANCELED"),
  DB_LOCK_NOT_AVAILABLE(
      "SKISX016",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.EXECUTION,
      "a required database lock could not be acquired",
      "LOCK_NOT_AVAILABLE"),
  DB_DEADLOCK(
      "SKISX017",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.EXECUTION,
      "concurrent transactions formed a deadlock",
      "DEADLOCK"),
  DB_SERIALIZATION_FAILURE(
      "SKISX018",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.EXECUTION,
      "a transaction could not be serialized",
      "SERIALIZATION_FAILURE"),
  DB_UNCATEGORIZED(
      "SKISX019",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.EXECUTION,
      "database failure could not be classified safely",
      "UNCATEGORIZED"),
  TRANSACTION_EXTERNAL_OWNERSHIP(
      "SKISX020",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.TRANSACTION,
      "connection provider delegates transaction ownership to an external manager"),
  TRANSACTION_BEGIN_FAILED(
      "SKISX021",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.TRANSACTION,
      "JDBC transaction could not begin"),
  TRANSACTION_COMMIT_OUTCOME_UNKNOWN(
      "SKISX022",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.TRANSACTION,
      "JDBC commit failed and the transaction outcome may be unknown"),
  TRANSACTION_ROLLBACK_OUTCOME_UNKNOWN(
      "SKISX023",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.TRANSACTION,
      "JDBC rollback failed and the transaction outcome may be unknown"),
  TRANSACTION_NOT_ACTIVE(
      "SKISX024",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.TRANSACTION,
      "transaction is not active"),
  TRANSACTION_CLOSE_FAILED(
      "SKISX025",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.TRANSACTION,
      "transaction cleanup could not restore or release its connection"),
  TRANSACTION_AFTER_COMMIT_FAILED(
      "SKISX026",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.TRANSACTION,
      "transaction committed but an after-commit callback failed"),
  SESSION_INACTIVE(
      "SKISX027",
      SkisErrorFamily.EXECUTION,
      SkisDiagnosticStage.TRANSACTION,
      "SKIS session is no longer active"),

  // SKISM: mutation semantics, protection, and optimistic locking.
  MUTATION_ENTITY_READ_ONLY(
      "SKISM001",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "read-only entity cannot be mutated"),
  MUTATION_NO_INSERTABLE_PROPERTIES(
      "SKISM002",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "entity has no insertable properties"),
  MUTATION_NO_UPDATABLE_PROPERTIES(
      "SKISM003",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "entity has no updatable properties"),
  MUTATION_PRIMARY_KEY_REQUIRED(
      "SKISM004",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "mutation Fast Path requires primary-key metadata"),
  MUTATION_FAST_PATH_PRIMARY_KEY(
      "SKISM005",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "mutation Fast Path requires exactly one primary-key property"),
  MUTATION_UNEXPECTED_ROW_COUNT(
      "SKISM006",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.EXECUTION,
      "mutation affected an unexpected number of rows"),
  MUTATION_ENTITY_TYPE_MISMATCH(
      "SKISM007",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.INPUT_CONSTRUCTION,
      "mutation entity value does not match the generated entity type"),
  MUTATION_VALUE_TYPE_MISMATCH(
      "SKISM008",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.INPUT_CONSTRUCTION,
      "mutation value does not match the property Java type"),
  MUTATION_OPTIMISTIC_LOCK_CONFLICT(
      "SKISM009",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.EXECUTION,
      "version-checked update no longer matches the stored entity version"),
  MUTATION_VERSION_READ_FAILED(
      "SKISM010",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.EXECUTION,
      "optimistic version could not be read from the entity"),
  MUTATION_VERSION_TYPE_MISMATCH(
      "SKISM011",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.SEMANTIC_VALIDATION,
      "optimistic version value does not match the declared version type"),
  MUTATION_PARAMETER_SHAPE(
      "SKISM012",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.COMPILATION,
      "dialect rendered an unexpected mutation parameter shape"),
  MUTATION_REQUIRED_VALUE_NULL(
      "SKISM013",
      SkisErrorFamily.MUTATION,
      SkisDiagnosticStage.INPUT_CONSTRUCTION,
      "required mutation property value is null"),
  MUTATION_ID_VALUE_INVALID(
      "SKISM014", SkisErrorFamily.MUTATION, SkisDiagnosticStage.INPUT_CONSTRUCTION,
      "primary-key input is invalid for its generation strategy"),
  MUTATION_ID_GENERATION_FAILED(
      "SKISM015", SkisErrorFamily.MUTATION, SkisDiagnosticStage.EXECUTION,
      "primary-key generation failed"),
  MUTATION_GENERATED_KEY_INVALID(
      "SKISM016", SkisErrorFamily.MUTATION, SkisDiagnosticStage.EXECUTION,
      "JDBC generated keys violated the single non-null key contract"),
  MUTATION_ID_ACCESS_FAILED(
      "SKISM017", SkisErrorFamily.MUTATION, SkisDiagnosticStage.INPUT_CONSTRUCTION,
      "generated primary-key reader failed"),
  MUTATION_ID_APPLY_FAILED(
      "SKISM018", SkisErrorFamily.MUTATION, SkisDiagnosticStage.EXECUTION,
      "successful insert primary key could not be applied to the entity");

  private final String code;
  private final SkisErrorFamily family;
  private final SkisDiagnosticStage defaultStage;
  private final String description;
  private final @Nullable String databaseCategory;

  SkisErrorCode(
      String code, SkisErrorFamily family, SkisDiagnosticStage defaultStage, String description) {
    this(code, family, defaultStage, description, null);
  }

  SkisErrorCode(
      String code,
      SkisErrorFamily family,
      SkisDiagnosticStage defaultStage,
      String description,
      @Nullable String databaseCategory) {
    this.code = requireCode(code, family);
    this.family = Objects.requireNonNull(family, "family");
    this.defaultStage = Objects.requireNonNull(defaultStage, "defaultStage");
    this.description = requireText(description);
    this.databaseCategory = databaseCategory;
  }

  /** Returns the stable explicit string encoding, for example {@code SKISQ001}. */
  public String code() {
    return code;
  }

  /** Returns the owning error-code family. */
  public SkisErrorFamily family() {
    return family;
  }

  /** Returns the stage used when a factory does not supply a more specific one. */
  public SkisDiagnosticStage defaultStage() {
    return defaultStage;
  }

  /** Returns the safe default description. It never contains caller values. */
  public String description() {
    return description;
  }

  /**
   * Returns the fixed {@code SqlExceptionCategory} name for database-category codes, or {@code
   * null} for codes that describe a phase or a non-database failure.
   */
  public @Nullable String databaseCategory() {
    return databaseCategory;
  }

  /** Returns whether this code represents a classified database failure. */
  public boolean isDatabaseCategory() {
    return databaseCategory != null;
  }

  private static String requireCode(String code, SkisErrorFamily family) {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(family, "family");
    if (!code.matches("[A-Z]{4,5}[0-9]{3}") || !code.startsWith(family.prefix())) {
      throw new IllegalArgumentException(
          "error code '" + code + "' does not belong to family " + family.name());
    }
    return code;
  }

  private static String requireText(String value) {
    Objects.requireNonNull(value, "description");
    if (value.isBlank()) {
      throw new IllegalArgumentException("description" + " must not be blank");
    }
    return value;
  }
}
