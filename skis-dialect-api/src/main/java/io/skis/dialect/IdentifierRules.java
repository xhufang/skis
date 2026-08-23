package io.skis.dialect;

/** Validates and quotes one physical SQL identifier. */
@FunctionalInterface
public interface IdentifierRules {

  /**
   * Quotes one identifier component such as a catalog, schema, table, column, or alias.
   *
   * <p>The input is always one component; callers must not pass a dot-separated qualified name.
   */
  String quote(String identifier);
}
