package io.skis.query;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable request for offset or forward-only keyset slice retrieval. */
public final class SliceRequest {

  enum Mode {
    OFFSET,
    KEYSET_FIRST,
    RESUME
  }

  private final Mode mode;
  private final long offset;
  private final int pageSize;
  private final @Nullable SliceContinuation continuation;

  private SliceRequest(
      Mode mode, long offset, int pageSize, @Nullable SliceContinuation continuation) {
    this.mode = Objects.requireNonNull(mode, "mode");
    if (offset < 0) {
      throw new IllegalArgumentException("offset must not be negative");
    }
    if (pageSize <= 0) {
      throw new IllegalArgumentException("pageSize must be positive");
    }
    this.offset = offset;
    this.pageSize = pageSize;
    this.continuation = continuation;
  }

  public static SliceRequest offset(long offset, int pageSize) {
    return new SliceRequest(Mode.OFFSET, offset, pageSize, null);
  }

  public static SliceRequest keysetFirst(int pageSize) {
    return new SliceRequest(Mode.KEYSET_FIRST, 0, pageSize, null);
  }

  public static SliceRequest resume(SliceContinuation continuation, int pageSize) {
    return new SliceRequest(
        Mode.RESUME, 0, pageSize, Objects.requireNonNull(continuation, "continuation"));
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
