package io.skis.mutation;

import java.io.Serial;

/** Reports that a version-checked update no longer matches the stored entity version. */
public final class OptimisticLockException extends MutationException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates a conflict for a version-checked update that affected no row. */
  public OptimisticLockException(String message) {
    super(message);
  }
}
