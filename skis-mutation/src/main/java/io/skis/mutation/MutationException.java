package io.skis.mutation;

import io.skis.core.SkisException;
import java.io.Serial;

/** Reports an invalid mutation or a failed mutation execution. */
public class MutationException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  public MutationException(String message) {
    super(message);
  }

  public MutationException(String message, Throwable cause) {
    super(message, cause);
  }
}
