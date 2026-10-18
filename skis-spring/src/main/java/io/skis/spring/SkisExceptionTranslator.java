package io.skis.spring;

import io.skis.core.NonUniqueResultException;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisErrorCode;
import io.skis.core.SkisException;
import io.skis.jdbc.SkisPersistenceException;
import io.skis.mutation.MutationException;
import io.skis.mutation.OptimisticLockException;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
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
import org.springframework.dao.support.PersistenceExceptionTranslator;

/**
 * Bridges SKIS persistence failures into Spring's portable {@code DataAccessException} hierarchy.
 *
 * <p>The JDBC boundary classifies a driver failure exactly once. This translator consumes the
 * resulting {@link SkisErrorCode} and never parses a message, inspects a driver code, or invokes a
 * dialect classifier again. Configuration, query-semantic, and dialect failures return {@code null}
 * so another translator or the caller retains the original SKIS exception.
 */
public final class SkisExceptionTranslator implements PersistenceExceptionTranslator {

  /** Creates a translator that consumes the classification already attached to each SKIS failure. */
  public SkisExceptionTranslator() {}

  /**
   * Translates only SKIS failures; unrelated runtime exceptions return {@code null} for delegation
   * to another Spring translator.
   */
  @Override
  public @Nullable DataAccessException translateExceptionIfPossible(RuntimeException exception) {
    Objects.requireNonNull(exception, "exception");
    if (!(exception instanceof SkisException skisFailure)) {
      return null;
    }
    switch (exception) {
      case OptimisticLockException ignored -> {
        return new OptimisticLockingFailureException(safeMessage(exception), exception);
      }
      case NonUniqueResultException nonUnique -> {
        return new IncorrectResultSizeDataAccessException(
            safeMessage(exception), 1, observedRows(nonUnique), exception);
      }
      case SkisPersistenceException ignored -> {
        return translatePersistence(skisFailure);
      }
      case MutationException ignored -> {
        return null;
      }
      default -> {
      }
    }
    return null;
  }

  private static @Nullable DataAccessException translatePersistence(SkisException failure) {
    String message = safeMessage(failure);
    return switch (failure.code()) {
      case DB_DUPLICATE_KEY -> new DuplicateKeyException(message, failure);
      case DB_FOREIGN_KEY_VIOLATION, DB_CONSTRAINT_VIOLATION ->
          new DataIntegrityViolationException(message, failure);
      case DB_TIMEOUT -> new QueryTimeoutException(message, failure);
      case DB_QUERY_CANCELED, DB_UNCATEGORIZED, JDBC_STATEMENT_CONFIGURE_FAILED,
           JDBC_RESULT_SET_CLOSE_FAILED, JDBC_STATEMENT_CLOSE_FAILED -> uncategorized(message, failure);
      case DB_LOCK_NOT_AVAILABLE -> new CannotAcquireLockException(message, failure);
      case DB_CONNECTION_FAILURE, JDBC_CONNECTION_ACQUIRE_FAILED, JDBC_CONNECTION_RELEASE_FAILED ->
          new DataAccessResourceFailureException(message, failure);
      case DB_DEADLOCK, DB_SERIALIZATION_FAILURE -> new ConcurrencyFailureException(message, failure);
      // A compiled parameter-layout failure is a framework contract violation, not a data-access
      // failure. Leave it for the caller instead of mislabeling it as a database problem.
      case JDBC_PARAMETER_BINDING_FAILED -> null;
      default -> null;
    };
  }

  private static int observedRows(NonUniqueResultException failure) {
    SkisDiagnostics.Facts facts = failure.diagnostics().facts();
    if (facts != null && facts.observedCount() != null) {
      return Math.max(NonUniqueResultException.OBSERVED_ROW_LOWER_BOUND, facts.observedCount());
    }
    return NonUniqueResultException.OBSERVED_ROW_LOWER_BOUND;
  }

  private static String safeMessage(RuntimeException exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? "SKIS persistence operation failed" : message;
  }

  private static DataAccessException uncategorized(String message, RuntimeException exception) {
    return new UncategorizedDataAccessException(message, exception) {
      @Serial private static final long serialVersionUID = 1L;
    };
  }
}
