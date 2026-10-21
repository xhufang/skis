package io.skis.query;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Immutable count-free slice for offset or keyset traversal. */
public record Slice<R extends @Nullable Object>(
    List<R> items, int pageSize, boolean hasNext, Optional<SliceContinuation> nextContinuation) {

  public Slice {
    items = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(items, "items")));
    Objects.requireNonNull(nextContinuation, "nextContinuation");
    if (pageSize <= 0 || items.size() > pageSize) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED, "slice items or pageSize are invalid");
    }
    if (hasNext != nextContinuation.isPresent()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_CONTINUATION_INVALID, "hasNext and nextContinuation must agree");
    }
  }

  /** Creates a slice and derives whether another slice is available from its continuation. */
  public static <R extends @Nullable Object> Slice<R> of(
      List<R> items, int pageSize, @Nullable SliceContinuation continuation) {
    return new Slice<>(items, pageSize, continuation != null, Optional.ofNullable(continuation));
  }
}
