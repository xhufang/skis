package io.skis.query;

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
      throw new IllegalArgumentException("slice items or pageSize are invalid");
    }
    if (hasNext != nextContinuation.isPresent()) {
      throw new IllegalArgumentException("hasNext and nextContinuation must agree");
    }
  }

  static <R extends @Nullable Object> Slice<R> of(
      List<R> items, int pageSize, @Nullable SliceContinuation continuation) {
    return new Slice<>(items, pageSize, continuation != null, Optional.ofNullable(continuation));
  }
}
