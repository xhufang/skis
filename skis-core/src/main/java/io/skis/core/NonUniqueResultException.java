package io.skis.core;

import java.io.Serial;

/** Reports that an operation requiring at most one row observed multiple rows. */
public final class NonUniqueResultException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates a non-unique-result failure with a structural query diagnostic. */
  public NonUniqueResultException(String message) {
    super(message);
  }
}
