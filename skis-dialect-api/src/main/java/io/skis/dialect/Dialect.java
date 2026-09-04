package io.skis.dialect;

import io.skis.sql.ast.StatementAst;

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

  /**
   * Performs pre-render dialect validation for the supplied portable statement.
   *
   * <p>The current preflight covers Join capabilities. Renderers must retain their own defensive
   * validation for direct callers.
   */
  default void validate(StatementAst statement) {
    DialectJoinFeatures.validate(id(), capabilities(), statement);
  }

  /** JDBC error classifier; returned instances must be thread-safe. */
  default ExceptionClassifier exceptionClassifier() {
    return ExceptionClassifier.NONE;
  }
}
