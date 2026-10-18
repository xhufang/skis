package io.skis.core;

import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Reports a failure while beginning, committing, rolling back, closing, or running an after-commit
 * callback for a transaction.
 */
public final class TransactionException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  private TransactionException(
      SkisErrorCode code, SkisDiagnostics diagnostics, @Nullable Throwable cause) {
    super(code, diagnostics, cause, SkisErrorFamily.EXECUTION);
  }

  /** Creates a transaction failure with a safe structural detail. */
  public static TransactionException of(SkisErrorCode code, String detail) {
    return of(code, detail, null);
  }

  /** Creates a transaction failure retaining its original cause. */
  public static TransactionException of(
      SkisErrorCode code, String detail, @Nullable Throwable cause) {
    String requiredDetail = Objects.requireNonNull(detail, "detail");
    if (requiredDetail.isBlank()) {
      throw new IllegalArgumentException("detail must not be blank");
    }
    SkisDiagnostics diagnostics = SkisDiagnostics.of(code.defaultStage(), requiredDetail);
    return of(code, diagnostics, cause);
  }

  /** Creates a transaction failure from explicitly supplied diagnostics. */
  public static TransactionException of(SkisErrorCode code, SkisDiagnostics diagnostics) {
    return of(code, diagnostics, null);
  }

  /** Creates a transaction failure from explicit diagnostics and an original cause. */
  public static TransactionException of(
      SkisErrorCode code, SkisDiagnostics diagnostics, @Nullable Throwable cause) {
    return new TransactionException(code, requireDiagnostics(code, diagnostics), cause);
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
}
