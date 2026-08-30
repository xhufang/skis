package io.skis.spring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.SqlExceptionCategory;
import io.skis.jdbc.QueryExecutionException;
import io.skis.mutation.MutationException;
import io.skis.mutation.OptimisticLockException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.UncategorizedDataAccessException;

class SkisExceptionTranslatorTest {

  @Test
  void translatesClassifiedQueryFailuresAndRetainsTheSkisCause() {
    SQLException sqlFailure = new SQLException("duplicate sensitive value", "23505", 17);
    QueryExecutionException skisFailure =
        QueryExecutionException.from("test", "SELECT id FROM pet WHERE name = ?", sqlFailure);
    SkisExceptionTranslator translator =
        new SkisExceptionTranslator(ignored -> SqlExceptionCategory.DUPLICATE_KEY);

    DataAccessException translated = translator.translateExceptionIfPossible(skisFailure);

    assertInstanceOf(DuplicateKeyException.class, translated);
    assertSame(skisFailure, translated.getCause());
    assertSame(sqlFailure, skisFailure.getCause());
    assertTrue(translated.getMessage().contains("sqlFingerprint="));
    assertFalse(translated.getMessage().contains("SELECT id"));
    assertFalse(translated.getMessage().contains("sensitive value"));
  }

  @Test
  void translatesWrappedMutationFailuresUsingTheConfiguredClassifier() {
    SQLException foreignKeyFailure = new SQLException("foreign key", "23503", 23);
    MutationException mutationFailure =
        new MutationException("cannot insert entity", foreignKeyFailure);
    SkisExceptionTranslator translator =
        new SkisExceptionTranslator(ignored -> SqlExceptionCategory.FOREIGN_KEY_VIOLATION);

    DataAccessException translated = translator.translateExceptionIfPossible(mutationFailure);

    assertInstanceOf(DataIntegrityViolationException.class, translated);
    assertSame(mutationFailure, translated.getCause());
    assertSame(foreignKeyFailure, mutationFailure.getCause());
  }

  @Test
  void usesTheCategoryAlreadyRetainedByTheMutationBoundary() {
    SQLException sqlFailure = new SQLException("duplicate", "23505", 24);
    MutationException mutationFailure =
        new MutationException(
            "cannot insert entity", sqlFailure, SqlExceptionCategory.DUPLICATE_KEY);

    DataAccessException translated =
        new SkisExceptionTranslator().translateExceptionIfPossible(mutationFailure);

    assertInstanceOf(DuplicateKeyException.class, translated);
    assertSame(mutationFailure, translated.getCause());
  }

  @Test
  void mapsEveryPortableFailureFamily() {
    SQLException sqlFailure = new SQLException("failure", "state", 1);

    assertInstanceOf(
        QueryTimeoutException.class,
        translate(sqlFailure, SqlExceptionCategory.TIMEOUT));
    assertInstanceOf(
        UncategorizedDataAccessException.class,
        translate(sqlFailure, SqlExceptionCategory.QUERY_CANCELED));
    assertInstanceOf(
        CannotAcquireLockException.class,
        translate(sqlFailure, SqlExceptionCategory.LOCK_NOT_AVAILABLE));
    assertInstanceOf(
        DataAccessResourceFailureException.class,
        translate(sqlFailure, SqlExceptionCategory.CONNECTION_FAILURE));
    assertInstanceOf(
        DataIntegrityViolationException.class,
        translate(sqlFailure, SqlExceptionCategory.CONSTRAINT_VIOLATION));
    assertInstanceOf(
        ConcurrencyFailureException.class,
        translate(sqlFailure, SqlExceptionCategory.DEADLOCK));
    assertInstanceOf(
        ConcurrencyFailureException.class,
        translate(sqlFailure, SqlExceptionCategory.SERIALIZATION_FAILURE));
    assertInstanceOf(
        UncategorizedDataAccessException.class,
        translate(sqlFailure, SqlExceptionCategory.UNCATEGORIZED));
    assertInstanceOf(
        OptimisticLockingFailureException.class,
        new SkisExceptionTranslator()
            .translateExceptionIfPossible(new OptimisticLockException("version conflict")));
  }

  @Test
  void classifierErrorsDoNotMaskTheOriginalJdbcFailure() {
    SQLException sqlFailure = new SQLException("failure", "42000", 25);
    AssertionError classifierFailure = new AssertionError("classifier failed");
    MutationException mutationFailure = new MutationException("mutation failed", sqlFailure);
    SkisExceptionTranslator translator =
        new SkisExceptionTranslator(
            ignored -> {
              throw classifierFailure;
            });

    DataAccessException translated = translator.translateExceptionIfPossible(mutationFailure);

    assertInstanceOf(UncategorizedDataAccessException.class, translated);
    assertSame(mutationFailure, translated.getCause());
    assertSame(sqlFailure, mutationFailure.getCause());
    assertSame(classifierFailure, sqlFailure.getSuppressed()[0]);
  }

  @Test
  void leavesUnrelatedAndNonJdbcSkisFailuresForOtherTranslators() {
    SkisExceptionTranslator translator = new SkisExceptionTranslator();

    assertNull(translator.translateExceptionIfPossible(new IllegalStateException("unrelated")));
    assertNull(
        translator.translateExceptionIfPossible(new MutationException("validation failure")));
  }

  private static DataAccessException translate(
      SQLException sqlFailure, SqlExceptionCategory category) {
    MutationException mutationFailure = new MutationException("mutation failed", sqlFailure);
    return new SkisExceptionTranslator(ignored -> category)
        .translateExceptionIfPossible(mutationFailure);
  }
}
