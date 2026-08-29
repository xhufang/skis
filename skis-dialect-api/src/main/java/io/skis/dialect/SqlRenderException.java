package io.skis.dialect;

import io.skis.core.SkisException;
import java.io.Serial;

/** Indicates that a valid AST cannot be represented by the selected dialect renderer. */
public final class SqlRenderException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates a rendering failure with a non-sensitive diagnostic message. */
  public SqlRenderException(String message) {
    super(message);
  }
}
