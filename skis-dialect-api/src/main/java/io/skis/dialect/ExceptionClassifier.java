package io.skis.dialect;

import java.sql.SQLException;

/**
 * Classifies a JDBC failure using dialect-specific SQLState and vendor-code contracts.
 * Implementations must be thread-safe and must not inspect parameter-bearing messages.
 */
@FunctionalInterface
public interface ExceptionClassifier {

  /** Classifier used by custom dialects that do not yet publish an error contract. */
  ExceptionClassifier NONE = ignored -> SqlExceptionCategory.UNCATEGORIZED;

  /** Returns a stable category, or {@link SqlExceptionCategory#UNCATEGORIZED}. */
  SqlExceptionCategory classify(SQLException exception);
}
