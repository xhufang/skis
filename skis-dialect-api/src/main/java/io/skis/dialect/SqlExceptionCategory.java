package io.skis.dialect;

/** Stable, vendor-neutral categories used to describe JDBC failures. */
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
  SERIALIZATION_FAILURE
}
