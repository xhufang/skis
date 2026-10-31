package io.skis.mutation;

import io.skis.core.*;
import java.io.Serial;
import java.util.Objects;

/** A failed batch or an inexact result, with completed and unexecuted input facts. */
public final class BatchWriteException extends SkisException {

  @Serial private static final long serialVersionUID = 1L;

  private final BatchResult result;

  public BatchWriteException(BatchResult result, SkisException cause) {
    super(cause.code(), cause.diagnostics(), cause, cause.code().family());
    this.result = Objects.requireNonNull(result, "result");
  }

  public BatchResult result() {
    return result;
  }
}
