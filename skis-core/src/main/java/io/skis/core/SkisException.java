package io.skis.core;

import java.io.Serial;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Root of every framework-defined unchecked runtime failure.
 *
 * <p>An exception carries a strong {@link SkisErrorCode} and immutable {@link SkisDiagnostics}. The
 * generated message is a value-free safe snapshot built only from the code description and the fixed
 * diagnostic groups. The original cause and suppressed failures remain available for troubleshooting,
 * but are deliberately not rendered into the default message or diagnostics.
 *
 * <p>The constructor is not public. Framework modules create instances through the constrained
 * factories on the concrete subclasses, which validate the code family and required fields. The root
 * does not wrap every Throwable that leaves a framework call stack; JDK value-operation exceptions and
 * user callback failures keep their original semantics.
 */
public abstract class SkisException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final SkisErrorCode code;
  private final SkisDiagnostics diagnostics;

  /**
   * Creates a framework failure.
   *
   * @param code stable Reason code
   * @param diagnostics immutable value-free diagnostics
   * @param cause original failure retained for troubleshooting, or {@code null}
   * @param allowedFamilies error-code families that this exception type may represent
   */
  protected SkisException(
      SkisErrorCode code,
      SkisDiagnostics diagnostics,
      @Nullable Throwable cause,
      SkisErrorFamily... allowedFamilies) {
    super(safeMessage(code, diagnostics), cause);
    this.code = Objects.requireNonNull(code, "code");
    this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    List<SkisErrorFamily> allowed = List.of(allowedFamilies);
    if (allowed.isEmpty() || !allowed.contains(code.family())) {
      throw new IllegalArgumentException(
          code.code()
              + " does not belong to the allowed families "
              + allowed
              + " of "
              + getClass().getSimpleName());
    }
  }

  /** Returns the stable Reason code. */
  public final SkisErrorCode code() {
    return code;
  }

  /** Returns the immutable value-free diagnostics. */
  public final SkisDiagnostics diagnostics() {
    return diagnostics;
  }

  /** Returns the stable explicit string encoding, for example {@code SKISQ001}. */
  public final String errorCode() {
    return code.code();
  }

  private static String safeMessage(SkisErrorCode code, SkisDiagnostics diagnostics) {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(diagnostics, "diagnostics");
    return "["
        + code.code()
        + "] "
        + code.description()
        + " ("
        + diagnostics.safeText()
        + ")";
  }
}
