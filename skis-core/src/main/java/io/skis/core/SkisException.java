package io.skis.core;

/** Base unchecked exception for all public SKIS failures. */
public class SkisException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates an exception with a safe diagnostic message. */
  public SkisException(String message) {
    super(message);
  }

  /** Creates an exception with a safe diagnostic message and its original cause. */
  public SkisException(String message, Throwable cause) {
    super(message, cause);
  }
}
