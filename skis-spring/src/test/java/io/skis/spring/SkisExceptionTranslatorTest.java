package io.skis.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.NonUniqueResultException;
import io.skis.core.QueryValidationException;
import io.skis.core.SkisConfigurationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.TransactionException;
import io.skis.dialect.spi.SqlExceptionCategory;
import io.skis.jdbc.JdbcExecutionException;
import io.skis.jdbc.JdbcPhase;
import io.skis.jdbc.QueryExecutionException;
import io.skis.mutation.OptimisticLockException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.UncategorizedDataAccessException;

class SkisExceptionTranslatorTest {

  @Test
  void translatesClassifiedQueryFailuresAndRetainsTheSkisCause() {
    SQLException sqlFailure = new SQLException("duplicate sensitive value", "23505", 17);
    QueryExecutionException skisFailure =
        QueryExecutionException.from(
            JdbcPhase.EXECUTE,
            "test",
            "SELECT id FROM pet WHERE name = ?",
            sqlFailure,
            SqlExceptionCategory.DUPLICATE_KEY);
    SkisExceptionTranslator translator = new SkisExceptionTranslator();

    DataAccessException translated = translator.translateExceptionIfPossible(skisFailure);

    assertInstanceOf(DuplicateKeyException.class, translated);
    assertSame(skisFailure, translated.getCause());
    assertSame(sqlFailure, skisFailure.getCause());
    assertEquals(SkisErrorCode.DB_DUPLICATE_KEY, skisFailure.code());
    assertTrue(translated.getMessage().contains("sqlFingerprint="));
    assertFalse(translated.getMessage().contains("SELECT id"));
    assertFalse(translated.getMessage().contains("sensitive value"));
  }

  @Test
  void consumesTheMutationBoundaryClassificationWithoutReclassifying() {
    SQLException sqlFailure = new SQLException("duplicate", "23505", 24);
    JdbcExecutionException mutationFailure =
        JdbcExecutionException.from(
            JdbcPhase.EXECUTE,
            io.skis.core.SkisStatementKind.INSERT,
            "test",
            "INSERT INTO pet (name) VALUES (?)",
            sqlFailure,
            SqlExceptionCategory.DUPLICATE_KEY);

    DataAccessException translated =
        new SkisExceptionTranslator().translateExceptionIfPossible(mutationFailure);

    assertInstanceOf(DuplicateKeyException.class, translated);
    assertSame(mutationFailure, translated.getCause());
    assertEquals(SkisErrorCode.DB_DUPLICATE_KEY, mutationFailure.code());
  }

  @Test
  void doesNotGuessACategoryFromAStateThatWasAlreadyUnclassified() {
    SQLException sqlFailure = new SQLException("duplicate", "23505", 25);
    QueryExecutionException uncategorized =
        QueryExecutionException.from(
            JdbcPhase.EXECUTE,
            "test",
            "INSERT INTO pet (name) VALUES (?)",
            sqlFailure,
            SqlExceptionCategory.UNCATEGORIZED);

    DataAccessException translated =
        new SkisExceptionTranslator().translateExceptionIfPossible(uncategorized);

    assertInstanceOf(UncategorizedDataAccessException.class, translated);
    assertEquals(SkisErrorCode.DB_UNCATEGORIZED, uncategorized.code());
  }

  @Test
  void mapsEveryPortableFailureFamily() {
    assertInstanceOf(QueryTimeoutException.class, translate(SqlExceptionCategory.TIMEOUT));
    assertInstanceOf(
        UncategorizedDataAccessException.class, translate(SqlExceptionCategory.QUERY_CANCELED));
    assertInstanceOf(
        CannotAcquireLockException.class, translate(SqlExceptionCategory.LOCK_NOT_AVAILABLE));
    assertInstanceOf(
        DataAccessResourceFailureException.class,
        translate(SqlExceptionCategory.CONNECTION_FAILURE));
    assertInstanceOf(
        DataIntegrityViolationException.class,
        translate(SqlExceptionCategory.CONSTRAINT_VIOLATION));
    assertInstanceOf(
        DataIntegrityViolationException.class,
        translate(SqlExceptionCategory.FOREIGN_KEY_VIOLATION));
    assertInstanceOf(ConcurrencyFailureException.class, translate(SqlExceptionCategory.DEADLOCK));
    assertInstanceOf(
        ConcurrencyFailureException.class, translate(SqlExceptionCategory.SERIALIZATION_FAILURE));
    assertInstanceOf(
        UncategorizedDataAccessException.class, translate(SqlExceptionCategory.UNCATEGORIZED));
    assertInstanceOf(
        OptimisticLockingFailureException.class,
        new SkisExceptionTranslator()
            .translateExceptionIfPossible(OptimisticLockException.of("version conflict")));
  }

  @Test
  void mapsMultipleRowsWithoutClaimingAPreciseTotal() {
    NonUniqueResultException failure =
        NonUniqueResultException.of("query expected at most one row");

    DataAccessException translated =
        new SkisExceptionTranslator().translateExceptionIfPossible(failure);

    IncorrectResultSizeDataAccessException resultSize =
        assertInstanceOf(IncorrectResultSizeDataAccessException.class, translated);
    assertEquals(1, resultSize.getExpectedSize());
    assertEquals(2, resultSize.getActualSize());
    assertSame(failure, translated.getCause());
    assertEquals(SkisErrorCode.QUERY_MULTIPLE_ROWS, failure.code());
  }

  @Test
  void preservesCauseAndSuppressedOfClassifiedJdbcFailures() {
    SQLException sqlFailure = new SQLException("failure", "42000", 25);
    SQLException releaseFailure = new SQLException("release", "08006", 26);
    QueryExecutionException failure =
        QueryExecutionException.from(
            JdbcPhase.EXECUTE, "test", "SELECT 1", sqlFailure, SqlExceptionCategory.UNCATEGORIZED);
    failure.addSuppressed(releaseFailure);

    DataAccessException translated =
        new SkisExceptionTranslator().translateExceptionIfPossible(failure);

    assertSame(failure, translated.getCause());
    assertSame(releaseFailure, failure.getSuppressed()[0]);
  }

  @Test
  void leavesUnrelatedAndNonJdbcSkisFailuresForOtherTranslators() {
    SkisExceptionTranslator translator = new SkisExceptionTranslator();

    assertNull(translator.translateExceptionIfPossible(new IllegalStateException("unrelated")));
    assertNull(
        translator.translateExceptionIfPossible(
            QueryValidationException.of(SkisErrorCode.QUERY_WHERE_CONTRACT, "invalid WHERE")));
    assertNull(
        translator.translateExceptionIfPossible(
            SkisConfigurationException.of(
                SkisErrorCode.ASSEMBLY_CONFIGURATION_INVALID, "missing dialect")));
  }

  @Test
  void doesNotReclassifyTransactionOrSessionFailuresAsDataAccessFailures() {
    SkisExceptionTranslator translator = new SkisExceptionTranslator();
    SkisErrorCode[] transactionCodes = {
      SkisErrorCode.TRANSACTION_EXTERNAL_OWNERSHIP,
      SkisErrorCode.TRANSACTION_BEGIN_FAILED,
      SkisErrorCode.TRANSACTION_COMMIT_OUTCOME_UNKNOWN,
      SkisErrorCode.TRANSACTION_ROLLBACK_OUTCOME_UNKNOWN,
      SkisErrorCode.TRANSACTION_NOT_ACTIVE,
      SkisErrorCode.TRANSACTION_CLOSE_FAILED,
      SkisErrorCode.TRANSACTION_AFTER_COMMIT_FAILED,
      SkisErrorCode.SESSION_INACTIVE
    };

    for (SkisErrorCode code : transactionCodes) {
      TransactionException failure =
          TransactionException.of(code, "transaction or session lifecycle failure");
      assertNull(translator.translateExceptionIfPossible(failure), code::code);
    }
  }

  private static DataAccessException translate(SqlExceptionCategory category) {
    QueryExecutionException failure =
        QueryExecutionException.from(
            JdbcPhase.EXECUTE,
            "test",
            "SELECT id FROM pet WHERE name = ?",
            new SQLException("failure", "state", 1),
            category);
    return new SkisExceptionTranslator().translateExceptionIfPossible(failure);
  }
}
