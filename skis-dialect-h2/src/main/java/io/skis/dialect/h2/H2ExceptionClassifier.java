package io.skis.dialect.h2;

import io.skis.dialect.ExceptionClassifier;
import io.skis.dialect.SqlExceptionCategory;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** H2 SQLState/vendor-code classifier for development and contract tests. */
final class H2ExceptionClassifier implements ExceptionClassifier {

  static final H2ExceptionClassifier INSTANCE = new H2ExceptionClassifier();

  private static final int LOCK_TIMEOUT_ERROR_CODE = 50200;
  private static final int CONNECTION_BROKEN_ERROR_CODE = 90067;

  private H2ExceptionClassifier() {}

  @Override
  public SqlExceptionCategory classify(SQLException exception) {
    SQLException current = Objects.requireNonNull(exception, "exception");
    SqlExceptionCategory fallback = SqlExceptionCategory.UNCATEGORIZED;
    for (int depth = 0; current != null && depth < 64; depth++) {
      String sqlState = current.getSQLState();
      SqlExceptionCategory category = classifyExact(sqlState, current.getErrorCode());
      if (category != SqlExceptionCategory.UNCATEGORIZED) {
        return category;
      }
      if (fallback == SqlExceptionCategory.UNCATEGORIZED) {
        fallback = classifyByStateClass(sqlState);
      }
      current = current.getNextException();
    }
    return fallback;
  }

  private static SqlExceptionCategory classifyExact(@Nullable String sqlState, int vendorCode) {
    if (vendorCode == LOCK_TIMEOUT_ERROR_CODE) {
      return SqlExceptionCategory.TIMEOUT;
    }
    if (vendorCode == CONNECTION_BROKEN_ERROR_CODE) {
      return SqlExceptionCategory.CONNECTION_FAILURE;
    }
    if (sqlState == null || sqlState.isBlank()) {
      return SqlExceptionCategory.UNCATEGORIZED;
    }
    return switch (sqlState) {
      case "23505" -> SqlExceptionCategory.DUPLICATE_KEY;
      case "23503" -> SqlExceptionCategory.FOREIGN_KEY_VIOLATION;
      case "57014" -> SqlExceptionCategory.QUERY_CANCELED;
      case "HYT00", "HYT01" -> SqlExceptionCategory.TIMEOUT;
      case "40001" -> SqlExceptionCategory.DEADLOCK;
      default -> SqlExceptionCategory.UNCATEGORIZED;
    };
  }

  private static SqlExceptionCategory classifyByStateClass(@Nullable String sqlState) {
    if (sqlState == null) {
      return SqlExceptionCategory.UNCATEGORIZED;
    }
    if (sqlState.startsWith("23")) {
      return SqlExceptionCategory.CONSTRAINT_VIOLATION;
    }
    if (sqlState.startsWith("08")) {
      return SqlExceptionCategory.CONNECTION_FAILURE;
    }
    return SqlExceptionCategory.UNCATEGORIZED;
  }
}
