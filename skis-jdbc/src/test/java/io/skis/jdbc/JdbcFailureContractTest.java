package io.skis.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.SkisErrorCode;
import io.skis.core.SkisOperation;
import io.skis.core.SkisStatementKind;
import io.skis.dialect.spi.SqlExceptionCategory;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Verifies the fixed JDBC category/phase to error-code contract and safe diagnostics. */
class JdbcFailureContractTest {

  private static final String SQL = "SELECT name FROM pet WHERE id = ?";
  private static final String SECRET = "secret-customer-value";

  @Test
  void mapsEveryDatabaseCategoryToItsFixedExecutionCode() {
    for (SqlExceptionCategory category : SqlExceptionCategory.values()) {
      SQLException driverFailure = new SQLException(SECRET, "23505", 17);
      QueryExecutionException failure =
          QueryExecutionException.from(JdbcPhase.EXECUTE, "test", SQL, driverFailure, category);

      assertEquals(category.errorCode(), failure.code(), category::name);
      assertEquals(SkisOperation.QUERY, failure.operation());
      assertEquals(JdbcPhase.EXECUTE, failure.phase());
      assertEquals(SkisStatementKind.SELECT, failure.statementKind());
      assertEquals("test", failure.dialectId());
      assertEquals(driverFailure, failure.getCause());
      assertEquals(category, failure.category());
      assertFalse(failure.getMessage().contains(SECRET));
      assertFalse(failure.getMessage().contains(SQL));
      assertTrue(failure.getMessage().contains("sqlFingerprint="));
    }
  }

  @Test
  void mapsResourceLifecyclePhasesToTheirOwnCodes() {
    assertPhaseCode(JdbcPhase.ACQUIRE, SkisErrorCode.JDBC_CONNECTION_ACQUIRE_FAILED);
    assertPhaseCode(JdbcPhase.CONFIGURE, SkisErrorCode.JDBC_STATEMENT_CONFIGURE_FAILED);
    assertPhaseCode(JdbcPhase.BIND, SkisErrorCode.JDBC_PARAMETER_BINDING_FAILED);
    assertPhaseCode(JdbcPhase.RESULT_SET_CLOSE, SkisErrorCode.JDBC_RESULT_SET_CLOSE_FAILED);
    assertPhaseCode(JdbcPhase.STATEMENT_CLOSE, SkisErrorCode.JDBC_STATEMENT_CLOSE_FAILED);
    assertPhaseCode(JdbcPhase.RELEASE, SkisErrorCode.JDBC_CONNECTION_RELEASE_FAILED);
  }

  @Test
  void mutationFailuresUseTheTypedMutationOperationAndStatementKind() {
    SQLException driverFailure = new SQLException(SECRET, "23505", 18);
    JdbcExecutionException failure =
        JdbcExecutionException.from(
            JdbcPhase.EXECUTE,
            SkisStatementKind.INSERT,
            "test",
            "INSERT INTO pet (name) VALUES (?)",
            driverFailure,
            SqlExceptionCategory.DUPLICATE_KEY);

    assertEquals(SkisErrorCode.DB_DUPLICATE_KEY, failure.code());
    assertEquals(SkisOperation.MUTATION, failure.operation());
    assertEquals(SkisStatementKind.INSERT, failure.statementKind());
    assertEquals(SqlExceptionCategory.DUPLICATE_KEY, failure.category());
    assertSame(driverFailure, failure.getCause());
    assertEquals("23505", failure.sqlState());
    assertEquals(Integer.valueOf(18), failure.vendorCode());
    assertFalse(failure.getMessage().contains(SECRET));
  }

  @Test
  void omitsDriverCodesThatWereNotSupplied() {
    SQLException driverFailure = new SQLException(SECRET);
    QueryExecutionException failure =
        QueryExecutionException.from(
            JdbcPhase.ACQUIRE, "test", SQL, driverFailure, SqlExceptionCategory.UNCATEGORIZED);

    assertNull(failure.sqlState());
    assertNull(failure.vendorCode());
    assertNull(failure.diagnostics().driver());
    assertFalse(failure.getMessage().contains("vendorCode=0"));
  }

  @Test
  void suppressesLaterResourceFailuresWithoutReplacingThePrimaryFailure() {
    SQLException primary = new SQLException("execute", "42000", 21);
    SQLException release = new SQLException("release", "08006", 22);
    QueryExecutionException failure =
        QueryExecutionException.from(
            JdbcPhase.EXECUTE, "test", SQL, primary, SqlExceptionCategory.UNCATEGORIZED);
    failure.addSuppressed(release);

    assertSame(primary, failure.getCause());
    assertSame(release, failure.getSuppressed()[0]);
  }

  private static void assertPhaseCode(JdbcPhase phase, SkisErrorCode expectedCode) {
    SQLException driverFailure = new SQLException(SECRET, "HY000", 31);
    QueryExecutionException failure =
        QueryExecutionException.from(
            phase, "test", SQL, driverFailure, SqlExceptionCategory.UNCATEGORIZED);

    assertEquals(expectedCode, failure.code());
    assertEquals(phase, failure.phase());
    assertEquals(SqlExceptionCategory.UNCATEGORIZED, failure.category());
    assertFalse(failure.getMessage().contains(SECRET));
  }
}
