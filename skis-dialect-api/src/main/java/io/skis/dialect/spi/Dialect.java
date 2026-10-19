package io.skis.dialect.spi;

import java.util.Objects;

/** Small composition root for database-specific SQL behavior. */
public interface Dialect {

  /** Stable lowercase identifier used by diagnostics and shared plan-cache keys. */
  String id();

  /** Identifier validation and quoting behavior. */
  IdentifierRules identifierRules();

  /** Capabilities supported by this dialect implementation. */
  DialectCapabilities capabilities();

  /**
   * Stable version of all dialect behavior that can affect validation, lowering, or rendered SQL.
   *
   * <p>The default covers the immutable {@link #capabilities()} set. An implementation whose
   * product version, configuration, or other behavior changes executable SQL without changing that
   * set must override this method with an equally stable value. Shared-plan identities must retain
   * the complete capability set as well; this integer is not a collision-free replacement for that
   * set.
   */
  default int capabilityVersion() {
    return capabilities().version();
  }

  /**
   * Returns whether this dialect exposes a complete, stable identity for shared query-plan caching.
   *
   * <p>The default is {@code false}: third-party dialects remain executable, but must bypass the
   * shared L1 plan cache until they explicitly opt in. An implementation that returns {@code true}
   * must keep its identifier, capabilities, capability version, validation, lowering, and rendering
   * behavior immutable and thread-safe for the lifetime of a plan catalog.
   */
  default boolean hasStablePlanCacheIdentity() {
    return false;
  }

  /**
   * Returns the connection-independent conservative bind-parameter limit the framework may rely on.
   *
   * <p>An explicit value is a framework cap, not a claim that every connection or driver mode has
   * that exact physical maximum. The default is explicitly unknown; callers must not interpret
   * unknown as unbounded. A connection-aware layer may retain or lower a declared limit, but must
   * not raise an explicit limit.
   */
  default StatementParameterLimit maxStatementParameters() {
    return StatementParameterLimit.unknown();
  }

  /** Renderer configured for this dialect. */
  SqlRenderer renderer();

  /**
   * Performs pre-lowering dialect validation for the exact controlled resolved stage.
   *
   * <p>The default recursively validates capabilities and qualified-table use against the stage's
   * own analysis. A dialect with additional custom rules overrides this method and must preserve
   * the same controlled input contract; it must not accept a bare statement/analysis pair.
   */
  default void validate(ResolvedStatement statement) {
    Objects.requireNonNull(statement, "statement");
    statement.validateCapabilities(id(), capabilities());
  }

  /**
   * Structural conversion for this dialect.
   *
   * <p>The default returns the exact input statement, meaning that portable normalization alone
   * expresses the final structure. Dialects that need native syntax differences override this
   * method; the controlled pipeline always runs portable normalization and post-lowering validation
   * after it.
   */
  default DialectLowering lowering() {
    return DialectLowering.identity();
  }

  /** JDBC error classifier; returned instances must be thread-safe. */
  default ExceptionClassifier exceptionClassifier() {
    return ExceptionClassifier.NONE;
  }
}
