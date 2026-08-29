package io.skis.jdbc;

import io.skis.core.SkisException;
import java.io.Serial;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Safe JDBC query failure retaining driver diagnostics without parameter values. */
public final class QueryExecutionException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  private final String dialectId;
  private final String sqlFingerprint;
  private final @Nullable String sqlState;
  private final int vendorCode;

  private QueryExecutionException(JdbcFailureDiagnostics diagnostics, SQLException cause) {
    super(diagnostics.message(), cause);
    this.dialectId = diagnostics.dialectId();
    this.sqlFingerprint = diagnostics.sqlFingerprint();
    this.sqlState = diagnostics.sqlState();
    this.vendorCode = diagnostics.vendorCode();
  }

  /** Translates a driver failure using only structural query diagnostics. */
  public static QueryExecutionException from(String dialectId, String sql, SQLException cause) {
    return from(JdbcFailureDiagnostics.Phase.EXECUTE, dialectId, sql, cause);
  }

  static QueryExecutionException from(
      JdbcFailureDiagnostics.Phase phase, String dialectId, String sql, SQLException cause) {
    JdbcFailureDiagnostics diagnostics =
        JdbcFailureDiagnostics.from("query", phase, dialectId, sql, cause);
    return new QueryExecutionException(diagnostics, cause);
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
    return JdbcFailureDiagnostics.fingerprint(sql);
  }
}
