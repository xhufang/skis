package io.skis.dialect;

import io.skis.sql.ast.QueryBlockAnalysis;
import io.skis.sql.ast.StatementAst;
import java.util.Objects;

/** Small composition root for database-specific SQL behavior. */
public interface Dialect {

  /** Stable lowercase identifier used by diagnostics and future plan-cache keys. */
  String id();

  /** Identifier validation and quoting behavior. */
  IdentifierRules identifierRules();

  /** Capabilities supported by this dialect implementation. */
  DialectCapabilities capabilities();

  /**
   * Stable version of all dialect behavior that can affect validation, lowering, or rendered SQL.
   *
   * <p>The compatibility default covers the immutable {@link #capabilities()} set. An
   * implementation whose product version, configuration, or other behavior changes executable SQL
   * without changing that set must override this method with an equally stable value. Shared-plan
   * identities must retain the complete capability set as well; this integer is not a
   * collision-free replacement for that set.
   */
  default int capabilityVersion() {
    return capabilities().version();
  }

  /**
   * Returns whether this dialect exposes a complete, stable identity for shared query-plan caching.
   *
   * <p>The compatibility default is {@code false}: existing third-party dialects remain executable,
   * but must bypass the shared L1 plan cache until they explicitly opt in. An implementation that
   * returns {@code true} must keep its identifier, capabilities, capability version, validation,
   * lowering, and rendering behavior immutable and thread-safe for the lifetime of a plan catalog.
   */
  default boolean hasStablePlanCacheIdentity() {
    return false;
  }

  /**
   * Returns the connection-independent conservative bind-parameter limit the framework may rely on.
   *
   * <p>An explicit value is a framework cap, not a claim that every connection or driver mode has
   * that exact physical maximum. The compatibility default is explicitly unknown; callers must not
   * interpret unknown as unbounded. A connection-aware layer may retain or lower a declared limit,
   * but must not raise an explicit limit.
   */
  default StatementParameterLimit maxStatementParameters() {
    return StatementParameterLimit.unknown();
  }

  /** Renderer configured for this dialect. */
  SqlRenderer renderer();

  /**
   * Performs pre-render dialect validation for the supplied portable statement.
   *
   * <p>The preflight recursively validates query-block capabilities. Renderers must retain their
   * own defensive validation for direct callers.
   */
  default void validate(StatementAst statement) {
    DialectQueryFeatures.validate(id(), capabilities(), statement);
  }

  /**
   * Returns whether the default resolved-validation entry point may consume an existing analysis.
   *
   * <p>The compatibility default is {@code false}: an existing dialect that overrides {@link
   * #validate(StatementAst)} keeps that behavior, even though it may resolve the query again.
   * Dialects whose validation is completely represented by {@link #capabilities()} may return
   * {@code true} to use the single-analysis path.
   */
  default boolean supportsResolvedQueryValidation() {
    return false;
  }

  /**
   * Performs dialect validation using the semantic analysis already produced for this statement.
   *
   * <p>This pipeline entry point avoids resolving the same query-block tree again between semantic
   * validation and dialect capability validation. The analysis must belong to the supplied complete
   * SELECT or COUNT statement; the caller currently owns that pairing because this compatibility
   * signature does not carry a provenance token. Existing dialects retain their legacy validation
   * override unless they explicitly opt in through {@link #supportsResolvedQueryValidation()} or
   * override this method.
   */
  default void validate(StatementAst statement, QueryBlockAnalysis analysis) {
    Objects.requireNonNull(analysis, "analysis");
    if (!supportsResolvedQueryValidation()) {
      validate(statement);
      return;
    }
    DialectQueryFeatures.validate(id(), capabilities(), statement, analysis);
  }

  /** JDBC error classifier; returned instances must be thread-safe. */
  default ExceptionClassifier exceptionClassifier() {
    return ExceptionClassifier.NONE;
  }
}
