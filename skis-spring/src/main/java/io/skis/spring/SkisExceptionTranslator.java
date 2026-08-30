package io.skis.spring;

import io.skis.core.SkisException;
import io.skis.dialect.Dialect;
import io.skis.dialect.ExceptionClassifier;
import io.skis.dialect.SqlExceptionCategory;
import io.skis.jdbc.SkisPersistenceException;
import io.skis.mutation.MutationException;
import io.skis.mutation.OptimisticLockException;
import java.io.Serial;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.UncategorizedDataAccessException;
import org.springframework.dao.support.PersistenceExceptionTranslator;

/**
 * Bridges SKIS persistence failures into Spring's portable {@code DataAccessException} hierarchy.
 */
public final class SkisExceptionTranslator implements PersistenceExceptionTranslator {

  private final ExceptionClassifier exceptionClassifier;

  /** Creates a translator that retains failures but uses no vendor-specific classification. */
  public SkisExceptionTranslator() {
    this(ExceptionClassifier.NONE);
  }

  /** Creates a translator using the selected dialect's JDBC error contract. */
  public SkisExceptionTranslator(Dialect dialect) {
    this(Objects.requireNonNull(dialect, "dialect").exceptionClassifier());
  }

  /** Creates a translator with an explicit thread-safe classifier. */
  public SkisExceptionTranslator(ExceptionClassifier exceptionClassifier) {
    this.exceptionClassifier = Objects.requireNonNull(exceptionClassifier, "exceptionClassifier");
  }

  /**
   * Translates only SKIS failures; unrelated runtime exceptions return {@code null} for delegation
   * to another Spring translator.
   */
  @Override
  public @Nullable DataAccessException translateExceptionIfPossible(RuntimeException exception) {
    Objects.requireNonNull(exception, "exception");
    if (!(exception instanceof SkisException)) {
      return null;
    }
    if (exception instanceof OptimisticLockException) {
      return new OptimisticLockingFailureException(safeMessage(exception), exception);
    }

    SQLException sqlException = findSqlException(exception);
    if (sqlException == null) {
      return null;
    }
    SqlExceptionCategory category = category(exception, sqlException);
    String message = safeMessage(exception);
    return switch (category) {
      case DUPLICATE_KEY -> new DuplicateKeyException(message, exception);
      case FOREIGN_KEY_VIOLATION, CONSTRAINT_VIOLATION ->
          new DataIntegrityViolationException(message, exception);
      case TIMEOUT -> new QueryTimeoutException(message, exception);
      case QUERY_CANCELED -> uncategorized(message, exception);
      case LOCK_NOT_AVAILABLE -> new CannotAcquireLockException(message, exception);
      case CONNECTION_FAILURE -> new DataAccessResourceFailureException(message, exception);
      case DEADLOCK, SERIALIZATION_FAILURE -> new ConcurrencyFailureException(message, exception);
      case UNCATEGORIZED -> uncategorized(message, exception);
    };
  }

  private SqlExceptionCategory category(RuntimeException exception, SQLException sqlException) {
    if (exception instanceof SkisPersistenceException persistenceException
        && persistenceException.category() != SqlExceptionCategory.UNCATEGORIZED) {
      return persistenceException.category();
    }
    if (exception instanceof MutationException mutationException
        && mutationException.category() != SqlExceptionCategory.UNCATEGORIZED) {
      return mutationException.category();
    }
    try {
      SqlExceptionCategory category = exceptionClassifier.classify(sqlException);
      return Objects.requireNonNull(category, "exception classifier result");
    } catch (RuntimeException | Error classifierFailure) {
      sqlException.addSuppressed(classifierFailure);
      return SqlExceptionCategory.UNCATEGORIZED;
    }
  }

  private static @Nullable SQLException findSqlException(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException) {
        return sqlException;
      }
      Throwable cause = current.getCause();
      if (cause == current) {
        return null;
      }
      current = cause;
    }
    return null;
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
