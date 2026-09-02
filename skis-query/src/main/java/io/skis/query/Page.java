package io.skis.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable offset page with an exact total count. */
public record Page<R extends @Nullable Object>(
    List<R> items,
    int pageIndex,
    int pageSize,
    long totalElements,
    long totalPages,
    boolean hasNext,
    boolean hasPrevious) {

  public Page {
    items = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(items, "items")));
    if (pageIndex < 0 || pageSize <= 0 || totalElements < 0 || totalPages < 0) {
      throw new IllegalArgumentException("page metadata is outside its valid range");
    }
    if (items.size() > pageSize) {
      throw new IllegalArgumentException("page items exceed pageSize");
    }
    long expectedPages = totalElements == 0 ? 0 : 1 + ((totalElements - 1) / pageSize);
    if (totalPages != expectedPages
        || hasNext != (pageIndex + 1L < expectedPages)
        || hasPrevious != (pageIndex > 0)) {
      throw new IllegalArgumentException("page navigation metadata is inconsistent");
    }
  }

  static <R extends @Nullable Object> Page<R> of(
      List<R> items, PageRequest request, long totalElements) {
    long totalPages = totalElements == 0 ? 0 : 1 + ((totalElements - 1) / request.pageSize());
    return new Page<>(
        items,
        request.pageIndex(),
        request.pageSize(),
        totalElements,
        totalPages,
        request.pageIndex() + 1L < totalPages,
        request.pageIndex() > 0);
  }
}
