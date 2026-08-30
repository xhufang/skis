package io.skis.jdbc;

import io.skis.dialect.SqlExceptionCategory;
import java.io.Serial;
import java.sql.SQLException;

/** Safe JDBC query failure retaining driver diagnostics without parameter values. */
public final class QueryExecutionException extends SkisPersistenceException {

  @Serial private static final long serialVersionUID = 1L;

  private QueryExecutionException(JdbcFailureDiagnostics diagnostics, SQLException cause) {
    super(diagnostics, cause);
  }

  /** Translates a driver failure using only structural query diagnostics. */
  public static QueryExecutionException from(String dialectId, String sql, SQLException cause) {
    return from(
        JdbcFailureDiagnostics.Phase.EXECUTE,
        dialectId,
        sql,
        cause,
        SqlExceptionCategory.UNCATEGORIZED);
  }

  static QueryExecutionException from(
      JdbcFailureDiagnostics.Phase phase, String dialectId, String sql, SQLException cause) {
    return from(phase, dialectId, sql, cause, SqlExceptionCategory.UNCATEGORIZED);
  }

  static QueryExecutionException from(
      JdbcFailureDiagnostics.Phase phase,
      String dialectId,
      String sql,
      SQLException cause,
      SqlExceptionCategory category) {
    JdbcFailureDiagnostics diagnostics =
        JdbcFailureDiagnostics.from("query", phase, dialectId, sql, cause, category);
    return new QueryExecutionException(diagnostics, cause);
  }

  static String fingerprint(String sql) {
    return JdbcFailureDiagnostics.fingerprint(sql);
  }
}
