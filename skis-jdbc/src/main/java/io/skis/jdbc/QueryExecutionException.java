package io.skis.jdbc;

import io.skis.core.SkisOperation;
import io.skis.core.SkisStatementKind;
import io.skis.dialect.spi.SqlExceptionCategory;
import java.io.Serial;
import java.sql.SQLException;

/** Safe JDBC query failure retaining driver diagnostics without parameter values. */
public final class QueryExecutionException extends SkisPersistenceException {

  @Serial private static final long serialVersionUID = 1L;

  private QueryExecutionException(JdbcFailureDiagnostics diagnostics, SQLException cause) {
    super(diagnostics, cause);
  }

  /**
   * Translates a classified driver failure from a strongly typed JDBC lifecycle phase.
   *
   * <p>Every call site must supply the category determined once by the JDBC boundary. The operation
   * is fixed to {@link SkisOperation#QUERY} and the statement kind to {@link
   * SkisStatementKind#SELECT}.
   */
  public static QueryExecutionException from(
      JdbcPhase phase,
      String dialectId,
      String sql,
      SQLException cause,
      SqlExceptionCategory category) {
    return create(
        JdbcFailureDiagnostics.of(
            SkisOperation.QUERY, phase, SkisStatementKind.SELECT, dialectId, sql, cause, category),
        cause);
  }

  static QueryExecutionException create(JdbcFailureDiagnostics diagnostics, SQLException cause) {
    return new QueryExecutionException(diagnostics, cause);
  }
}
