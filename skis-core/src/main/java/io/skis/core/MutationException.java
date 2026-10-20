package io.skis.core;

import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Reports an invalid mutation, a violated write-protection rule, or an optimistic-lock conflict.
 *
 * <p>Database failures raised while executing a write remain {@code JdbcExecutionException} with an
 * {@code SKISX} database-category code. They are not wrapped in this exception: the database reason
 * keeps one authoritative code and is not duplicated for INSERT/UPDATE/DELETE callers.
 */
public class MutationException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates a mutation failure; subclasses may narrow the accepted code family. */
  protected MutationException(
      SkisErrorCode code,
      SkisDiagnostics diagnostics,
      @Nullable Throwable cause,
      SkisErrorFamily... allowedFamilies) {
    super(code, diagnostics, cause, allowedFamilies);
  }

  /** Creates a mutation semantics failure with a safe structural detail. */
  public static MutationException of(SkisErrorCode code, String detail) {
    return of(code, detail, null);
  }

  /** Creates a mutation semantics failure retaining its original cause. */
  public static MutationException of(SkisErrorCode code, String detail, @Nullable Throwable cause) {
    String requiredDetail = Objects.requireNonNull(detail, "detail");
    if (requiredDetail.isBlank()) {
      throw new IllegalArgumentException("detail must not be blank");
    }
    SkisDiagnostics diagnostics = SkisDiagnostics.of(code.defaultStage(), requiredDetail);
    return of(code, diagnostics, cause);
  }

  /** Creates a mutation semantics failure from explicitly supplied diagnostics. */
  public static MutationException of(SkisErrorCode code, SkisDiagnostics diagnostics) {
    return of(code, diagnostics, null);
  }

  /** Creates a mutation semantics failure from explicit diagnostics and an original cause. */
  public static MutationException of(
      SkisErrorCode code, SkisDiagnostics diagnostics, @Nullable Throwable cause) {
    return new MutationException(
        code, requireDiagnostics(code, diagnostics), cause, SkisErrorFamily.MUTATION);
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
