package io.skis.core;

import java.io.Serial;

/** Reports invalid runtime assembly, generated indexes, or incompatible framework configuration. */
public final class SkisConfigurationException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  public SkisConfigurationException(String message) {
    super(message);
  }

  public SkisConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
