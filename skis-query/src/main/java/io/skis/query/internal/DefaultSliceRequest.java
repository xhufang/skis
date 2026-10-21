package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.SliceContinuation;
import io.skis.query.SliceRequest;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable request for offset or forward-only keyset slice retrieval. */
final class DefaultSliceRequest implements SliceRequest {

  enum Mode {
    OFFSET,
    KEYSET_FIRST,
    RESUME
  }

  private final Mode mode;
  private final long offset;
  private final int pageSize;
  private final @Nullable SliceContinuation continuation;

  private DefaultSliceRequest(
      Mode mode, long offset, int pageSize, @Nullable SliceContinuation continuation) {
    this.mode = Objects.requireNonNull(mode, "mode");
    if (offset < 0) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED, "offset must not be negative");
    }
    if (pageSize <= 0) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED, "pageSize must be positive");
    }
    this.offset = offset;
    this.pageSize = pageSize;
    this.continuation = continuation;
  }

  static DefaultSliceRequest offset(long offset, int pageSize) {
    return new DefaultSliceRequest(Mode.OFFSET, offset, pageSize, null);
  }

  static DefaultSliceRequest keysetFirst(int pageSize) {
    return new DefaultSliceRequest(Mode.KEYSET_FIRST, 0, pageSize, null);
  }

  static DefaultSliceRequest resume(SliceContinuation continuation, int pageSize) {
    return new DefaultSliceRequest(
        Mode.RESUME,
        0,
        pageSize,
        QueryModels.continuation(Objects.requireNonNull(continuation, "continuation")));
  }

  public int pageSize() {
    return pageSize;
  }

  Mode mode() {
    return mode;
  }

  long offset() {
    return offset;
  }

  SliceContinuation continuation() {
    return Objects.requireNonNull(continuation, "continuation");
  }
}
