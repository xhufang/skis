package io.skis.dialect;

/** Small composition root for database-specific SQL behavior. */
public interface Dialect {

  /** Stable lowercase identifier used by diagnostics and future plan-cache keys. */
  String id();

  /** Identifier validation and quoting behavior. */
  IdentifierRules identifierRules();

  /** Capabilities supported by this dialect implementation. */
  DialectCapabilities capabilities();

  /** Renderer configured for this dialect. */
  SqlRenderer renderer();
}
