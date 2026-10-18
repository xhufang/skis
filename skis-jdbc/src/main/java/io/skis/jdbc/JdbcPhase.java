package io.skis.jdbc;

import io.skis.core.SkisErrorCode;
import io.skis.dialect.spi.SqlExceptionCategory;
import org.jspecify.annotations.Nullable;

/**
 * Strongly typed JDBC lifecycle phase in which a driver failure occurred.
 *
 * <p>The phase is paired with the single authoritative {@link SqlExceptionCategory}. Execution uses
 * the category's fixed error code; connection acquisition, statement configuration, binding, and
 * resource close use phase-specific codes so that a release failure is not reported as a duplicate
 * key merely because the driver reported one while closing.
 */
public enum JdbcPhase {
  /** Acquiring a connection from the configured provider. */
  ACQUIRE("connection-acquire", SkisErrorCode.JDBC_CONNECTION_ACQUIRE_FAILED),

  /** Applying statement options or provider statement configuration. */
  CONFIGURE("statement-configuration", SkisErrorCode.JDBC_STATEMENT_CONFIGURE_FAILED),

  /** Binding compiled parameters to a prepared statement. */
  BIND("parameter-binding", SkisErrorCode.JDBC_PARAMETER_BINDING_FAILED),

  /** Executing a prepared statement or reading a result set. */
  EXECUTE("execution", null),

  /** Closing a result set. */
  RESULT_SET_CLOSE("result-set-close", SkisErrorCode.JDBC_RESULT_SET_CLOSE_FAILED),

  /** Closing a prepared statement. */
  STATEMENT_CLOSE("statement-close", SkisErrorCode.JDBC_STATEMENT_CLOSE_FAILED),

  /** Releasing a connection back to its provider. */
  RELEASE("connection-release", SkisErrorCode.JDBC_CONNECTION_RELEASE_FAILED);

  private final String label;
  private final @Nullable SkisErrorCode fixedCode;

  JdbcPhase(String label, @Nullable SkisErrorCode fixedCode) {
    this.label = label;
    this.fixedCode = fixedCode;
  }

  /** Returns the stable lower-case phase label used in safe diagnostic text. */
  public String label() {
    return label;
  }

  /**
   * Returns the fixed error code for this phase and classification.
   *
   * <p>Execution failures always use the database category's code. Lifecycle phases keep their own
   * code; the classification remains available in diagnostics.
   */
  public SkisErrorCode errorCode(SqlExceptionCategory category) {
    return fixedCode != null ? fixedCode : category.errorCode();
  }
}
