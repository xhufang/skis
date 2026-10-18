package io.skis.jdbc;

import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisErrorCode;
import io.skis.core.SkisOperation;
import io.skis.core.SkisStatementKind;
import io.skis.dialect.spi.SqlExceptionCategory;
import io.skis.jdbc.internal.JdbcFingerprint;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable value-independent diagnostics shared by query and mutation JDBC failures. */
final class JdbcFailureDiagnostics {

  private final SkisErrorCode code;
  private final SkisOperation operation;
  private final JdbcPhase phase;
  private final @Nullable SkisStatementKind statementKind;
  private final String dialectId;
  private final String sqlFingerprint;
  private final @Nullable String sqlState;
  private final @Nullable Integer vendorCode;
  private final SqlExceptionCategory category;
  private final SkisDiagnostics diagnostics;

  private JdbcFailureDiagnostics(
      SkisErrorCode code,
      SkisOperation operation,
      JdbcPhase phase,
      @Nullable SkisStatementKind statementKind,
      String dialectId,
      String sqlFingerprint,
      @Nullable String sqlState,
      @Nullable Integer vendorCode,
      SqlExceptionCategory category) {
    this.code = code;
    this.operation = operation;
    this.phase = phase;
    this.statementKind = statementKind;
    this.dialectId = dialectId;
    this.sqlFingerprint = sqlFingerprint;
    this.sqlState = sqlState;
    this.vendorCode = vendorCode;
    this.category = category;
    this.diagnostics = buildDiagnostics();
  }

  static JdbcFailureDiagnostics of(
      SkisOperation operation,
      JdbcPhase phase,
      @Nullable SkisStatementKind statementKind,
      String dialectId,
      String sql,
      SQLException cause,
      SqlExceptionCategory category) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(dialectId, "dialectId");
    Objects.requireNonNull(sql, "sql");
    Objects.requireNonNull(cause, "cause");
    Objects.requireNonNull(category, "category");
    SkisErrorCode code = phase.errorCode(category);
    if (code.isDatabaseCategory() && !category.name().equals(code.databaseCategory())) {
      throw new IllegalArgumentException(
          code.code() + " does not map to database category " + category);
    }
    return new JdbcFailureDiagnostics(
        code,
        operation,
        phase,
        statementKind,
        dialectId,
        fingerprint(sql),
        cause.getSQLState(),
        knownVendorCode(cause),
        category);
  }

  SkisErrorCode code() {
    return code;
  }

  SkisOperation operation() {
    return operation;
  }

  JdbcPhase phase() {
    return phase;
  }

  @Nullable SkisStatementKind statementKind() {
    return statementKind;
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

  @Nullable Integer vendorCode() {
    return vendorCode;
  }

  SqlExceptionCategory category() {
    return category;
  }

  SkisDiagnostics diagnostics() {
    return diagnostics;
  }

  private SkisDiagnostics buildDiagnostics() {
    var builder =
        SkisDiagnostics.builder(code.defaultStage())
            .operation(operation)
            .phase(phase.label())
            .sqlFingerprint(sqlFingerprint)
            .dialectId(dialectId)
            .driver(sqlState, vendorCode);
    if (statementKind != null && statementKind != SkisStatementKind.UNKNOWN) {
      builder.statementKind(statementKind);
    }
    if (category != SqlExceptionCategory.UNCATEGORIZED || code.isDatabaseCategory()) {
      builder.databaseCategory(category.name());
    }
    return builder.build();
  }

  static String fingerprint(String sql) {
    return JdbcFingerprint.of(sql);
  }

  private static @Nullable Integer knownVendorCode(SQLException cause) {
    int vendorCode = cause.getErrorCode();
    return vendorCode == 0 ? null : vendorCode;
  }
}
