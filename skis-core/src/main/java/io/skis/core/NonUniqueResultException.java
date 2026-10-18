package io.skis.core;

import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Reports that an operation requiring at most one row observed more than one.
 *
 * <p>The diagnostic records only the observed lower bound of two rows. It never claims a precise total
 * row count when the remaining result set has not been consumed.
 */
public final class NonUniqueResultException extends SkisException {

  /** Number of rows sufficient to prove that the result is not unique. */
  public static final int OBSERVED_ROW_LOWER_BOUND = 2;

  @Serial private static final long serialVersionUID = 1L;

  private NonUniqueResultException(SkisDiagnostics diagnostics, @Nullable Throwable cause) {
    super(
        SkisErrorCode.QUERY_MULTIPLE_ROWS,
        diagnostics,
        cause,
        SkisErrorFamily.QUERY);
  }

  /** Creates a non-unique-result failure with a safe structural detail. */
  public static NonUniqueResultException of(String detail) {
    return of(detail, null);
  }

  /** Creates a non-unique-result failure retaining an original cause. */
  public static NonUniqueResultException of(String detail, @Nullable Throwable cause) {
    String requiredDetail = Objects.requireNonNull(detail, "detail");
    if (requiredDetail.isBlank()) {
      throw new IllegalArgumentException("detail must not be blank");
    }
    return of(
        SkisDiagnostics.builder(SkisDiagnosticStage.RESULT_DECODING)
            .observedCount(OBSERVED_ROW_LOWER_BOUND)
            .detail(requiredDetail)
            .build(),
        cause);
  }

  /** Creates a non-unique-result failure from explicit diagnostics. */
  public static NonUniqueResultException of(SkisDiagnostics diagnostics) {
    return of(diagnostics, null);
  }

  /** Creates a non-unique-result failure from explicit diagnostics and an original cause. */
  public static NonUniqueResultException of(
      SkisDiagnostics diagnostics, @Nullable Throwable cause) {
    return new NonUniqueResultException(requireDiagnostics(diagnostics), cause);
  }

  private static SkisDiagnostics requireDiagnostics(SkisDiagnostics diagnostics) {
    SkisDiagnostics required = Objects.requireNonNull(diagnostics, "diagnostics");
    if (required.stage() != SkisErrorCode.QUERY_MULTIPLE_ROWS.defaultStage()) {
      throw new IllegalArgumentException(
          SkisErrorCode.QUERY_MULTIPLE_ROWS.code()
              + " diagnostics must use stage "
              + SkisErrorCode.QUERY_MULTIPLE_ROWS.defaultStage());
    }
    SkisDiagnostics.Facts facts = required.facts();
    if (facts == null
        || facts.observedCount() == null
        || facts.observedCount() < OBSERVED_ROW_LOWER_BOUND
        || facts.detail() == null
        || facts.detail().isBlank()) {
      throw new IllegalArgumentException(
          SkisErrorCode.QUERY_MULTIPLE_ROWS.code()
              + " diagnostics require observedCount>="
              + OBSERVED_ROW_LOWER_BOUND
              + " and detail");
    }
    return required;
  }
}
