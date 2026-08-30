package io.skis.core;

import java.io.Serial;

/**
 * Reports a failure while beginning, committing, rolling back, closing, or running an after-commit
 * callback for a transaction.
 */
public final class TransactionException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates a transaction failure with a safe diagnostic message. */
  public TransactionException(String message) {
    super(message);
  }

  /** Creates a transaction failure retaining its original cause. */
  public TransactionException(String message, Throwable cause) {
    super(message, cause);
  }
}
