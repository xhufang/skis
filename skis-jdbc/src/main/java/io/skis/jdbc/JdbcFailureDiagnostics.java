package io.skis.jdbc;

import io.skis.dialect.spi.SqlExceptionCategory;
import io.skis.jdbc.internal.JdbcFingerprint;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Internal value-independent diagnostics shared by query and mutation JDBC failures. */
final class JdbcFailureDiagnostics {

  private final String operation;
  private final String phase;
  private final String dialectId;
  private final String sqlFingerprint;
  private final @Nullable String sqlState;
  private final int vendorCode;
  private final SqlExceptionCategory category;

  private JdbcFailureDiagnostics(
      String operation,
      String phase,
      String dialectId,
      String sqlFingerprint,
      @Nullable String sqlState,
      int vendorCode,
      SqlExceptionCategory category) {
    this.operation = operation;
    this.phase = phase;
    this.dialectId = dialectId;
    this.sqlFingerprint = sqlFingerprint;
    this.sqlState = sqlState;
    this.vendorCode = vendorCode;
    this.category = category;
  }

  static JdbcFailureDiagnostics from(
      String operation,
      String phase,
      String dialectId,
      String sql,
      SQLException cause,
      SqlExceptionCategory category) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(phase, "phase");
    if (phase.isBlank()) {
      throw new IllegalArgumentException("phase must not be blank");
    }
    Objects.requireNonNull(dialectId, "dialectId");
    Objects.requireNonNull(sql, "sql");
    Objects.requireNonNull(cause, "cause");
    Objects.requireNonNull(category, "category");
    return new JdbcFailureDiagnostics(
        operation,
        phase,
        dialectId,
        fingerprint(sql),
        cause.getSQLState(),
        cause.getErrorCode(),
        category);
  }

  String operation() {
    return operation;
  }

  String phase() {
    return phase;
  }

  String dialectId() {
    return dialectId;
  }

  String sqlFingerprint() {
    return sqlFingerprint;
  }

  @Nullable String sqlState() {
    return sqlState;
  }

  int vendorCode() {
    return vendorCode;
  }

  SqlExceptionCategory category() {
    return category;
  }

  String message() {
    return "JDBC "
        + operation
        + " failed [phase="
        + phase
        + ", dialect="
        + dialectId
        + ", sqlFingerprint="
        + sqlFingerprint
        + ", sqlState="
        + safeState(sqlState)
        + ", vendorCode="
        + vendorCode
        + ", category="
        + category
        + "]";
  }

  static String fingerprint(String sql) {
    return JdbcFingerprint.of(sql);
  }

  private static String safeState(@Nullable String sqlState) {
    return sqlState == null || sqlState.isBlank() ? "unknown" : sqlState;
  }
}
