package io.skis.query;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;

/** Zero-based offset page request. */
public record PageRequest(int pageIndex, int pageSize) {

  public PageRequest {
    if (pageIndex < 0) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED, "pageIndex must not be negative");
    }
    if (pageSize <= 0) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED, "pageSize must be positive");
    }
  }

  public static PageRequest page(int pageIndex, int pageSize) {
    return new PageRequest(pageIndex, pageSize);
  }

  long offset() {
    return (long) pageIndex * pageSize;
  }
}
