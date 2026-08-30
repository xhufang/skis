package io.skis.jdbc;

import io.skis.dialect.SqlExceptionCategory;
import java.io.Serial;
import java.sql.SQLException;

/** Safe low-level JDBC failure retaining driver diagnostics without parameter values. */
public final class JdbcExecutionException extends SkisPersistenceException {

  @Serial private static final long serialVersionUID = 1L;

  private JdbcExecutionException(JdbcFailureDiagnostics diagnostics, SQLException cause) {
    super(diagnostics, cause);
  }

  /** Translates a driver failure using structural statement diagnostics only. */
  public static JdbcExecutionException from(
      String operation, String dialectId, String sql, SQLException cause) {
    return from(
        operation,
        JdbcFailureDiagnostics.Phase.EXECUTE,
        dialectId,
        sql,
        cause,
        SqlExceptionCategory.UNCATEGORIZED);
  }

  static JdbcExecutionException from(
      String operation,
      JdbcFailureDiagnostics.Phase phase,
      String dialectId,
      String sql,
      SQLException cause) {
    return from(operation, phase, dialectId, sql, cause, SqlExceptionCategory.UNCATEGORIZED);
  }

  static JdbcExecutionException from(
      String operation,
      JdbcFailureDiagnostics.Phase phase,
      String dialectId,
      String sql,
      SQLException cause,
      SqlExceptionCategory category) {
    JdbcFailureDiagnostics diagnostics =
        JdbcFailureDiagnostics.from(operation, phase, dialectId, sql, cause, category);
    return new JdbcExecutionException(diagnostics, cause);
  }
}
