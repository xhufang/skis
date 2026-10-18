package io.skis.dialect.spi;

import io.skis.core.SkisErrorCode;

/**
 * Stable, vendor-neutral categories used to describe JDBC failures.
 *
 * <p>The category is determined exactly once at the JDBC boundary and has a fixed mapping to a
 * {@link SkisErrorCode}. Spring translation and callers consume that result; they must not parse
 * exception messages or classify the same driver failure again.
 */
public enum SqlExceptionCategory {
  /** The dialect cannot classify the failure safely. */
  UNCATEGORIZED,

  /** A unique key or primary key was duplicated. */
  DUPLICATE_KEY,

  /** A foreign-key relationship was violated. */
  FOREIGN_KEY_VIOLATION,

  /** Another integrity constraint such as NOT NULL or CHECK was violated. */
  CONSTRAINT_VIOLATION,

  /** Statement execution exceeded a database or JDBC timeout. */
  TIMEOUT,

  /**
   * Statement execution was canceled.
   *
   * <p>Some databases reuse their cancellation state for a database-enforced timeout, so callers
   * must not assume that this category always represents an explicit user cancellation.
   */
  QUERY_CANCELED,

  /** A statement could not acquire a required database lock. */
  LOCK_NOT_AVAILABLE,

  /** The database connection could not be established or remained unusable. */
  CONNECTION_FAILURE,

  /** Concurrent transactions formed a deadlock. */
  DEADLOCK,

  /** A transaction could not be serialized and may be retried by application policy. */
  SERIALIZATION_FAILURE;

  /** Returns the fixed error code for this database category. */
  public SkisErrorCode errorCode() {
    return switch (this) {
      case UNCATEGORIZED -> SkisErrorCode.DB_UNCATEGORIZED;
      case DUPLICATE_KEY -> SkisErrorCode.DB_DUPLICATE_KEY;
      case FOREIGN_KEY_VIOLATION -> SkisErrorCode.DB_FOREIGN_KEY_VIOLATION;
      case CONSTRAINT_VIOLATION -> SkisErrorCode.DB_CONSTRAINT_VIOLATION;
      case TIMEOUT -> SkisErrorCode.DB_TIMEOUT;
      case QUERY_CANCELED -> SkisErrorCode.DB_QUERY_CANCELED;
      case LOCK_NOT_AVAILABLE -> SkisErrorCode.DB_LOCK_NOT_AVAILABLE;
      case CONNECTION_FAILURE -> SkisErrorCode.DB_CONNECTION_FAILURE;
      case DEADLOCK -> SkisErrorCode.DB_DEADLOCK;
      case SERIALIZATION_FAILURE -> SkisErrorCode.DB_SERIALIZATION_FAILURE;
    };
  }
}
