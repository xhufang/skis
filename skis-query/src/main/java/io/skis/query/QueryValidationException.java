package io.skis.query;

import io.skis.core.SkisException;
import java.io.Serial;

/** Reports a query shape or argument that cannot be compiled safely. */
public final class QueryValidationException extends SkisException {

  @Serial
  private static final long serialVersionUID = 1L;

  public QueryValidationException(String message) {
    super(message);
  }

  public QueryValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
