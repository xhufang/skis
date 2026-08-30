package io.skis.core;

import java.io.Serial;

/** Reports invalid runtime assembly, generated indexes, or incompatible framework configuration. */
public final class SkisConfigurationException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates a configuration failure with a safe diagnostic message. */
  public SkisConfigurationException(String message) {
    super(message);
  }

  /** Creates a configuration failure retaining its original cause. */
  public SkisConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
