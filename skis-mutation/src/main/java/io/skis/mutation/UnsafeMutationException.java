package io.skis.mutation;

import io.skis.core.MutationException;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisErrorCode;
import io.skis.core.SkisErrorFamily;
import java.io.Serial;

/** A conditional write did not supply WHERE or explicit full-table permission. */
public final class UnsafeMutationException extends MutationException {

  @Serial private static final long serialVersionUID = 1L;

  private UnsafeMutationException() {
    super(
        SkisErrorCode.MUTATION_UNSAFE,
        SkisDiagnostics.of(
            SkisErrorCode.MUTATION_UNSAFE.defaultStage(),
            "conditional write requires WHERE or allowAllRows()"),
        null,
        SkisErrorFamily.MUTATION);
  }

  public static UnsafeMutationException missingWhere() {
    return new UnsafeMutationException();
  }
}
