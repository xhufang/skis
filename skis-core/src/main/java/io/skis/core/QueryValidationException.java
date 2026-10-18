package io.skis.core;

import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Reports a query construction, semantic, result-shape, pagination, or continuation contract that
 * cannot be satisfied.
 *
 * <p>The exception is deliberately shared by all query-family failures. The specific reason is the
 * {@link SkisErrorCode}; creating one exception class per validation point would add no capture value.
 */
public final class QueryValidationException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  private QueryValidationException(
      SkisErrorCode code, SkisDiagnostics diagnostics, @Nullable Throwable cause) {
    super(code, diagnostics, cause, SkisErrorFamily.QUERY);
  }

  /** Creates a query contract failure with a safe structural detail. */
  public static QueryValidationException of(SkisErrorCode code, String detail) {
    return of(code, detail, null);
  }

  /** Creates a query contract failure retaining its original cause. */
  public static QueryValidationException of(
      SkisErrorCode code, String detail, @Nullable Throwable cause) {
    String requiredDetail = requireDetail(detail);
    SkisDiagnostics diagnostics = SkisDiagnostics.of(code.defaultStage(), requiredDetail);
    return of(code, diagnostics, cause);
  }

  /** Creates a query contract failure from explicitly supplied diagnostics. */
  public static QueryValidationException of(SkisErrorCode code, SkisDiagnostics diagnostics) {
    return of(code, diagnostics, null);
  }

  /**
   * Creates a query contract failure from a framework validation cause using caller-supplied safe
   * structural detail.
   *
   * <p>The original cause is retained for troubleshooting, but its message is never copied into the
   * default message or diagnostics because lower layers may include caller values or complete SQL.
   */
  public static QueryValidationException ofCause(
      SkisErrorCode code, IllegalArgumentException cause, String fallbackDetail) {
    return of(code, fallbackDetail, Objects.requireNonNull(cause, "cause"));
  }

  /** Creates a query contract failure from explicit diagnostics and an original cause. */
  public static QueryValidationException of(
      SkisErrorCode code, SkisDiagnostics diagnostics, @Nullable Throwable cause) {
    return new QueryValidationException(code, requireDiagnostics(code, diagnostics), cause);
  }

  private static SkisDiagnostics requireDiagnostics(
      SkisErrorCode code, SkisDiagnostics diagnostics) {
    SkisErrorCode requiredCode = Objects.requireNonNull(code, "code");
    SkisDiagnostics required = Objects.requireNonNull(diagnostics, "diagnostics");
    if (required.stage() != requiredCode.defaultStage()) {
      throw new IllegalArgumentException(
          requiredCode.code() + " diagnostics must use stage " + requiredCode.defaultStage());
    }
    SkisDiagnostics.Facts facts = required.facts();
    if (facts == null || facts.detail() == null || facts.detail().isBlank()) {
      throw new IllegalArgumentException(requiredCode.code() + " diagnostics require detail");
    }
    return required;
  }

  private static String requireDetail(String detail) {
    String required = Objects.requireNonNull(detail, "detail");
    if (required.isBlank()) {
      throw new IllegalArgumentException("detail must not be blank");
    }
    return required;
  }
}
