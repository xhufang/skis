package io.skis.jdbc;

import io.skis.core.SkisException;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Safe JDBC query failure retaining driver diagnostics without parameter values. */
public final class QueryExecutionException extends SkisException {

  private static final long serialVersionUID = 1L;

  private final String dialectId;
  private final String sqlFingerprint;
  private final @Nullable String sqlState;
  private final int vendorCode;

  private QueryExecutionException(
      String message, SQLException cause, String dialectId, String sqlFingerprint) {
    super(message, cause);
    this.dialectId = dialectId;
    this.sqlFingerprint = sqlFingerprint;
    this.sqlState = cause.getSQLState();
    this.vendorCode = cause.getErrorCode();
  }

  /** Translates a driver failure using only structural query diagnostics. */
  public static QueryExecutionException from(String dialectId, String sql, SQLException cause) {
    String fingerprint = fingerprint(sql);
    String message =
        "JDBC query failed [dialect="
            + dialectId
            + ", sqlFingerprint="
            + fingerprint
            + ", sqlState="
            + safeState(cause.getSQLState())
            + ", vendorCode="
            + cause.getErrorCode()
            + "]";
    return new QueryExecutionException(message, cause, dialectId, fingerprint);
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

  static String fingerprint(String sql) {
    long hash = 0xcbf29ce484222325L;
    for (int index = 0; index < sql.length(); index++) {
      hash ^= sql.charAt(index);
      hash *= 0x100000001b3L;
    }
    return Long.toUnsignedString(hash, 16);
  }

  private static String safeState(@Nullable String sqlState) {
    return sqlState == null || sqlState.isBlank() ? "unknown" : sqlState;
  }
}
