package io.skis.dialect;

import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisErrorCode;
import io.skis.core.SkisErrorFamily;
import io.skis.core.SkisException;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Reports that a valid AST cannot be represented by the selected dialect renderer.
 *
 * <p>External dialect implementations construct this exception through the public factories and
 * public {@link SkisDiagnostics} contract; they never need framework-internal helpers. The default
 * message and diagnostics omit complete SQL and never include statement parameters or result
 * values.
 */
public final class SqlRenderException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  private SqlRenderException(
      SkisErrorCode code, SkisDiagnostics diagnostics, @Nullable Throwable cause) {
    super(code, diagnostics, cause, SkisErrorFamily.DIALECT);
  }

  /** Creates a render failure for a known dialect and a safe structural detail. */
  public static SqlRenderException of(SkisErrorCode code, String dialectId, String detail) {
    return of(code, dialectId, null, detail);
  }

  /** Creates a render failure naming the dialect and, when known, the missing capability. */
  public static SqlRenderException of(
      SkisErrorCode code, String dialectId, @Nullable String missingCapability, String detail) {
    Objects.requireNonNull(dialectId, "dialectId");
    String requiredDetail = Objects.requireNonNull(detail, "detail");
    if (dialectId.isBlank() || requiredDetail.isBlank()) {
      throw new IllegalArgumentException("dialectId and detail must not be blank");
    }
    SkisDiagnostics diagnostics =
        SkisDiagnostics.builder(code.defaultStage())
            .dialectId(dialectId)
            .missingCapability(missingCapability)
            .detail(requiredDetail)
            .build();
    return of(code, diagnostics);
  }

  /** Creates a render failure from explicitly supplied diagnostics. */
  public static SqlRenderException of(SkisErrorCode code, SkisDiagnostics diagnostics) {
    return of(code, diagnostics, null);
  }

  /** Creates a render failure from explicit diagnostics and an original cause. */
  public static SqlRenderException of(
      SkisErrorCode code, SkisDiagnostics diagnostics, @Nullable Throwable cause) {
    return new SqlRenderException(code, requireDiagnostics(code, diagnostics), cause);
  }

  private static SkisDiagnostics requireDiagnostics(
      SkisErrorCode code, SkisDiagnostics diagnostics) {
    SkisErrorCode requiredCode = Objects.requireNonNull(code, "code");
    SkisDiagnostics required = Objects.requireNonNull(diagnostics, "diagnostics");
    if (required.stage() != requiredCode.defaultStage()) {
      throw new IllegalArgumentException(
          requiredCode.code() + " diagnostics must use stage " + requiredCode.defaultStage());
    }
    SkisDiagnostics.Dialect dialect = required.dialect();
    SkisDiagnostics.Facts facts = required.facts();
    if (dialect == null || dialect.dialectId() == null || dialect.dialectId().isBlank()) {
      throw new IllegalArgumentException(requiredCode.code() + " diagnostics require dialectId");
    }
    if (facts == null || facts.detail() == null || facts.detail().isBlank()) {
      throw new IllegalArgumentException(requiredCode.code() + " diagnostics require detail");
    }
    return required;
  }
}
