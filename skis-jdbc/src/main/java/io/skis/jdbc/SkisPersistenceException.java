package io.skis.jdbc;

import io.skis.core.SkisErrorFamily;
import io.skis.core.SkisException;
import io.skis.core.SkisOperation;
import io.skis.core.SkisStatementKind;
import io.skis.dialect.spi.SqlExceptionCategory;
import java.io.Serial;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Base JDBC persistence failure retaining safe structural and original driver diagnostics. */
public abstract class SkisPersistenceException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  private final SkisOperation operation;
  private final JdbcPhase phase;
  private final @Nullable SkisStatementKind statementKind;
  private final String dialectId;
  private final String sqlFingerprint;
  private final @Nullable String sqlState;
  private final @Nullable Integer vendorCode;
  private final SqlExceptionCategory category;

  SkisPersistenceException(JdbcFailureDiagnostics diagnostics, SQLException cause) {
    super(diagnostics.code(), diagnostics.diagnostics(), cause, SkisErrorFamily.EXECUTION);
    this.operation = diagnostics.operation();
    this.phase = diagnostics.phase();
    this.statementKind = diagnostics.statementKind();
    this.dialectId = diagnostics.dialectId();
    this.sqlFingerprint = diagnostics.sqlFingerprint();
    this.sqlState = diagnostics.sqlState();
    this.vendorCode = diagnostics.vendorCode();
    this.category = diagnostics.category();
  }

  /** Returns the strongly typed query or mutation operation. */
  public final SkisOperation operation() {
    return operation;
  }

  /** Returns the JDBC lifecycle phase in which the failure occurred. */
  public final JdbcPhase phase() {
    return phase;
  }

  /** Returns the SQL statement kind when known, or {@code null} when it is not reported. */
  public final @Nullable SkisStatementKind statementKind() {
    return statementKind;
  }

  /** Returns the dialect identifier used by the failed plan. */
  public final String dialectId() {
    return dialectId;
  }

  /** Returns the value-independent SQL fingerprint; parameters and query tags are excluded. */
  public final String sqlFingerprint() {
    return sqlFingerprint;
  }

  /** Returns the driver SQLState, or {@code null} when the driver supplied none. */
  public final @Nullable String sqlState() {
    return sqlState;
  }

  /** Returns the vendor-specific JDBC error code, or {@code null} when the driver supplied none. */
  public final @Nullable Integer vendorCode() {
    return vendorCode;
  }

  /** Returns the single authoritative dialect-classified failure category. */
  public final SqlExceptionCategory category() {
    return category;
  }
}
