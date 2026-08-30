package io.skis.mutation;

import io.skis.core.SkisException;
import java.io.Serial;

/** Reports an invalid mutation or a failed mutation execution. */
public class MutationException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates a mutation failure with a safe structural diagnostic. */
  public MutationException(String message) {
    super(message);
  }

  /** Creates a mutation failure retaining its original cause. */
  public MutationException(String message, Throwable cause) {
    super(message, cause);
  }
}
