package io.skis.core;

/**
 * Stable families of runtime error codes.
 *
 * <p>The family determines the public code prefix. It identifies the kind of failure, not the place
 * where it was thrown; the same reason may be reported from several detection points and must reuse
 * the same {@link SkisErrorCode}.
 */
public enum SkisErrorFamily {
  /** Assembly, configuration, generated-index, and entity-metadata declarations. */
  ASSEMBLY("SKISA", "assembly, configuration, or metadata"),

  /** Query construction, semantics, result shape, and non-null mapping contracts. */
  QUERY("SKISQ", "query construction, semantics, or result contract"),

  /** Dialect capability, rendering, lowering, and structural conversion contracts. */
  DIALECT("SKISD", "dialect capability or SQL rendering"),

  /** JDBC database failures, transaction handling, and resource lifecycle. */
  EXECUTION("SKISX", "JDBC, database, transaction, or resource lifecycle"),

  /** Mutation semantics, protection rules, and optimistic locking. */
  MUTATION("SKISM", "mutation semantics or write protection");

  private final String prefix;
  private final String description;

  SkisErrorFamily(String prefix, String description) {
    this.prefix = prefix;
    this.description = description;
  }

  /** Returns the three-character prefix shared by every code in this family. */
  public String prefix() {
    return prefix;
  }

  /** Returns a short human-readable scope used in generated messages. */
  public String description() {
    return description;
  }
}
