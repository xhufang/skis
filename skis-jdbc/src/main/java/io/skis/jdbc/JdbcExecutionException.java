package io.skis.jdbc;

import io.skis.core.SkisException;
import java.io.Serial;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Safe low-level JDBC failure retaining driver diagnostics without parameter values. */
public final class JdbcExecutionException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  private final String operation;
  private final String dialectId;
  private final String sqlFingerprint;
  private final @Nullable String sqlState;
  private final int vendorCode;

  private JdbcExecutionException(JdbcFailureDiagnostics diagnostics, SQLException cause) {
    super(diagnostics.message(), cause);
    this.operation = diagnostics.operation();
    this.dialectId = diagnostics.dialectId();
    this.sqlFingerprint = diagnostics.sqlFingerprint();
    this.sqlState = diagnostics.sqlState();
    this.vendorCode = diagnostics.vendorCode();
  }

  /** Translates a driver failure using structural statement diagnostics only. */
  public static JdbcExecutionException from(
      String operation, String dialectId, String sql, SQLException cause) {
    return from(operation, JdbcFailureDiagnostics.Phase.EXECUTE, dialectId, sql, cause);
  }

  static JdbcExecutionException from(
      String operation,
      JdbcFailureDiagnostics.Phase phase,
      String dialectId,
      String sql,
      SQLException cause) {
    JdbcFailureDiagnostics diagnostics =
        JdbcFailureDiagnostics.from(operation, phase, dialectId, sql, cause);
    return new JdbcExecutionException(diagnostics, cause);
  }

  /** Returns the structural mutation operation name. */
  public String operation() {
    return operation;
  }

  /** Returns the dialect identifier used by the failed plan. */
  public String dialectId() {
    return dialectId;
  }

  /** Returns the value-independent SQL fingerprint; parameter values are never included. */
  public String sqlFingerprint() {
    return sqlFingerprint;
  }

  /** Returns the driver SQLState, or {@code null} when the driver supplied none. */
  public @Nullable String sqlState() {
    return sqlState;
  }

  /** Returns the vendor-specific JDBC error code. */
  public int vendorCode() {
    return vendorCode;
  }
}
