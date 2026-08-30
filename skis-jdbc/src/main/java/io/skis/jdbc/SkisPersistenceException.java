package io.skis.jdbc;

import io.skis.core.SkisException;
import io.skis.dialect.SqlExceptionCategory;
import java.io.Serial;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Base JDBC persistence failure retaining safe structural and original driver diagnostics. */
public abstract class SkisPersistenceException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  private final String operation;
  private final String phase;
  private final String dialectId;
  private final String sqlFingerprint;
  private final @Nullable String sqlState;
  private final int vendorCode;
  private final SqlExceptionCategory category;

  SkisPersistenceException(JdbcFailureDiagnostics diagnostics, SQLException cause) {
    super(diagnostics.message(), cause);
    this.operation = diagnostics.operation();
    this.phase = diagnostics.phase();
    this.dialectId = diagnostics.dialectId();
    this.sqlFingerprint = diagnostics.sqlFingerprint();
    this.sqlState = diagnostics.sqlState();
    this.vendorCode = diagnostics.vendorCode();
    this.category = diagnostics.category();
  }

  /** Returns the structural query or mutation operation. */
  public String operation() {
    return operation;
  }

  /** Returns the JDBC lifecycle phase in which the failure occurred. */
  public String phase() {
    return phase;
  }

  /** Returns the dialect identifier used by the failed plan. */
  public String dialectId() {
    return dialectId;
  }

  /** Returns the value-independent SQL fingerprint; parameters and query tags are excluded. */
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

  /** Returns the dialect-classified failure category. */
  public SqlExceptionCategory category() {
    return category;
  }

  /** Returns the original driver failure. */
  public SQLException sqlException() {
    return (SQLException) getCause();
  }
}
