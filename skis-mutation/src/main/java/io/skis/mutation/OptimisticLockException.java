package io.skis.mutation;

import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisErrorCode;
import io.skis.core.SkisErrorFamily;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Reports that a version-checked update no longer matches the stored entity version. */
public final class OptimisticLockException extends MutationException {

  @Serial private static final long serialVersionUID = 1L;

  private OptimisticLockException(SkisDiagnostics diagnostics, @Nullable Throwable cause) {
    super(
        SkisErrorCode.MUTATION_OPTIMISTIC_LOCK_CONFLICT,
        diagnostics,
        cause,
        SkisErrorFamily.MUTATION);
  }

  /** Creates a conflict for a version-checked update that affected no row. */
  public static OptimisticLockException of(String detail) {
    return of(detail, null);
  }

  /** Creates a conflict retaining an original cause. */
  public static OptimisticLockException of(String detail, @Nullable Throwable cause) {
    String requiredDetail = Objects.requireNonNull(detail, "detail");
    if (requiredDetail.isBlank()) {
      throw new IllegalArgumentException("detail must not be blank");
    }
    return of(
        SkisDiagnostics.of(
            SkisErrorCode.MUTATION_OPTIMISTIC_LOCK_CONFLICT.defaultStage(), requiredDetail),
        cause);
  }

  /** Creates a conflict from explicitly supplied diagnostics. */
  public static OptimisticLockException of(SkisDiagnostics diagnostics) {
    return of(diagnostics, null);
  }

  /** Creates a conflict from explicit diagnostics and an original cause. */
  public static OptimisticLockException of(
      SkisDiagnostics diagnostics, @Nullable Throwable cause) {
    return new OptimisticLockException(requireDiagnostics(diagnostics), cause);
  }

  private static SkisDiagnostics requireDiagnostics(SkisDiagnostics diagnostics) {
    SkisDiagnostics required = Objects.requireNonNull(diagnostics, "diagnostics");
    if (required.stage() != SkisErrorCode.MUTATION_OPTIMISTIC_LOCK_CONFLICT.defaultStage()) {
      throw new IllegalArgumentException(
          SkisErrorCode.MUTATION_OPTIMISTIC_LOCK_CONFLICT.code()
              + " diagnostics must use stage "
              + SkisErrorCode.MUTATION_OPTIMISTIC_LOCK_CONFLICT.defaultStage());
    }
    SkisDiagnostics.Facts facts = required.facts();
    if (facts == null || facts.detail() == null || facts.detail().isBlank()) {
      throw new IllegalArgumentException(
          SkisErrorCode.MUTATION_OPTIMISTIC_LOCK_CONFLICT.code()
              + " diagnostics require detail");
    }
    return required;
  }
}
