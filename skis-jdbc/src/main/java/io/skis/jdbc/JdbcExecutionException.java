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

  private JdbcExecutionException(
      String message,
      SQLException cause,
      String operation,
      String dialectId,
      String sqlFingerprint) {
    super(message, cause);
    this.operation = operation;
    this.dialectId = dialectId;
    this.sqlFingerprint = sqlFingerprint;
    this.sqlState = cause.getSQLState();
    this.vendorCode = cause.getErrorCode();
  }

  /** Translates a driver failure using structural statement diagnostics only. */
  public static JdbcExecutionException from(
      String operation, String dialectId, String sql, SQLException cause) {
    String fingerprint = QueryExecutionException.fingerprint(sql);
    String state = cause.getSQLState();
    String message =
        "JDBC "
            + operation
            + " failed [dialect="
            + dialectId
            + ", sqlFingerprint="
            + fingerprint
            + ", sqlState="
            + (state == null || state.isBlank() ? "unknown" : state)
            + ", vendorCode="
            + cause.getErrorCode()
            + "]";
    return new JdbcExecutionException(message, cause, operation, dialectId, fingerprint);
  }

  public String operation() {
    return operation;
  }

  public String dialectId() {
    return dialectId;
  }

  public String sqlFingerprint() {
    return sqlFingerprint;
  }

  public @Nullable String sqlState() {
    return sqlState;
  }

  public int vendorCode() {
    return vendorCode;
  }
}
