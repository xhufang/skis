package io.skis.dialect;

import io.skis.core.SkisException;

/** Indicates that a valid AST cannot be represented by the selected dialect renderer. */
public final class SqlRenderException extends SkisException {

  /** Creates a rendering failure with a non-sensitive diagnostic message. */
  public SqlRenderException(String message) {
    super(message);
  }
}
