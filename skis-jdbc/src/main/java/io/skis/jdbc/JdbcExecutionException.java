package io.skis.jdbc;

import io.skis.core.SkisOperation;
import io.skis.core.SkisStatementKind;
import io.skis.dialect.spi.SqlExceptionCategory;
import java.io.Serial;
import java.sql.SQLException;

/** Safe low-level JDBC failure retaining driver diagnostics without parameter values. */
public final class JdbcExecutionException extends SkisPersistenceException {

  @Serial private static final long serialVersionUID = 1L;

  private JdbcExecutionException(JdbcFailureDiagnostics diagnostics, SQLException cause) {
    super(diagnostics, cause);
  }

  /**
   * Translates a classified driver failure from a strongly typed JDBC lifecycle phase.
   *
   * <p>Every call site must supply the category determined once by the JDBC boundary. The operation
   * is fixed to {@link SkisOperation#MUTATION}; the statement kind is the compiled mutation shape.
   */
  public static JdbcExecutionException from(
      JdbcPhase phase,
      SkisStatementKind statementKind,
      String dialectId,
      String sql,
      SQLException cause,
      SqlExceptionCategory category) {
    return create(
        JdbcFailureDiagnostics.of(
            SkisOperation.MUTATION, phase, statementKind, dialectId, sql, cause, category),
        cause);
  }

  static JdbcExecutionException create(JdbcFailureDiagnostics diagnostics, SQLException cause) {
    return new JdbcExecutionException(diagnostics, cause);
  }
}
