package io.skis.dialect.postgresql;

import io.skis.dialect.ExceptionClassifier;
import io.skis.dialect.SqlExceptionCategory;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** PostgreSQL SQLState classifier for the public JDBC failure categories. */
final class PostgreSqlExceptionClassifier implements ExceptionClassifier {

  static final PostgreSqlExceptionClassifier INSTANCE = new PostgreSqlExceptionClassifier();

  private PostgreSqlExceptionClassifier() {}

  @Override
  public SqlExceptionCategory classify(SQLException exception) {
    SQLException current = Objects.requireNonNull(exception, "exception");
    SqlExceptionCategory fallback = SqlExceptionCategory.UNCATEGORIZED;
    for (int depth = 0; current != null && depth < 64; depth++) {
      String sqlState = current.getSQLState();
      SqlExceptionCategory category = classifyExact(sqlState);
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

  private static SqlExceptionCategory classifyExact(@Nullable String sqlState) {
    if (sqlState == null || sqlState.isBlank()) {
      return SqlExceptionCategory.UNCATEGORIZED;
    }
    return switch (sqlState) {
      case "23505" -> SqlExceptionCategory.DUPLICATE_KEY;
      case "23503" -> SqlExceptionCategory.FOREIGN_KEY_VIOLATION;
      case "57014" -> SqlExceptionCategory.QUERY_CANCELED;
      case "55P03" -> SqlExceptionCategory.LOCK_NOT_AVAILABLE;
      case "57P01", "57P02", "57P03", "57P04", "57P05" -> SqlExceptionCategory.CONNECTION_FAILURE;
      case "40P01" -> SqlExceptionCategory.DEADLOCK;
      case "40001" -> SqlExceptionCategory.SERIALIZATION_FAILURE;
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
