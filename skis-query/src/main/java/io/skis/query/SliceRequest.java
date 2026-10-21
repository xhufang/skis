package io.skis.query;

import io.skis.query.internal.QueryDsl;

/** Immutable framework-owned request for offset or forward-only keyset slice retrieval. */
public interface SliceRequest {

  static SliceRequest offset(long offset, int pageSize) {
    return QueryDsl.offsetSlice(offset, pageSize);
  }

  static SliceRequest keysetFirst(int pageSize) {
    return QueryDsl.firstKeysetSlice(pageSize);
  }

  static SliceRequest resume(SliceContinuation continuation, int pageSize) {
    return QueryDsl.resumeSlice(continuation, pageSize);
  }

  int pageSize();
}
