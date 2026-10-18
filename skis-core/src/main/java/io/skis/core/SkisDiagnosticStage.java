package io.skis.core;

/**
 * Stable diagnostic stages at which a framework failure can be detected.
 *
 * <p>The stage is structural context, not a replacement for the error code. Codes that can occur at
 * several stages keep their reason and are distinguished by this field.
 */
public enum SkisDiagnosticStage {
  /** Public DSL, AST, pagination, continuation, or option input construction. */
  INPUT_CONSTRUCTION("input-construction"),

  /** Runtime assembly of generated providers, catalogs, indexes, or executors. */
  ASSEMBLY("assembly"),

  /** Entity or generated-model metadata declaration. */
  METADATA("metadata"),

  /** Executor, session, or plan-cache configuration. */
  CONFIGURATION("configuration"),

  /** Plan-cache capacity or expiry configuration. */
  CACHE_CONFIGURATION("cache-configuration"),

  /** Query description construction before compilation. */
  QUERY_CONSTRUCTION("query-construction"),

  /** Portable SQL and result-shape semantic validation. */
  SEMANTIC_VALIDATION("semantic-validation"),

  /** Page or slice request and continuation handling. */
  PAGINATION("pagination"),

  /** Slice continuation decoding and validation. */
  CONTINUATION("continuation"),

  /** Result shape, projection mapping, or decoder layout validation. */
  RESULT_SHAPE("result-shape"),

  /** Binding of logical parameters to concrete values. */
  PARAMETER_BINDING("parameter-binding"),

  /** Dialect capability validation before rendering. */
  DIALECT_VALIDATION("dialect-validation"),

  /** Dialect structural lowering. */
  LOWERING("lowering"),

  /** Post-lowering structural and result-shape validation. */
  POST_VALIDATION("post-validation"),

  /** SQL rendering and final parameter layout. */
  RENDERING("rendering"),

  /** Whole-plan compilation and layout checks. */
  COMPILATION("compilation"),

  /** JDBC statement execution or driver interaction. */
  EXECUTION("execution"),

  /** Row or value decoding after a successful JDBC read. */
  RESULT_DECODING("result-decoding"),

  /** Connection or statement acquisition. */
  RESOURCE_ACQUIRE("resource-acquire"),

  /** Result-set, statement, or connection close and release. */
  RESOURCE_CLOSE("resource-close"),

  /** Transaction begin, commit, rollback, or completion. */
  TRANSACTION("transaction"),

  /** Caller usage of a non-thread-safe runtime object such as a cursor or stream. */
  USAGE("usage");

  private final String label;

  SkisDiagnosticStage(String label) {
    this.label = label;
  }

  /** Returns the stable lower-case label used in safe diagnostic text. */
  public String label() {
    return label;
  }
}
