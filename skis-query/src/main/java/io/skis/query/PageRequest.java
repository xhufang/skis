package io.skis.query;

/** Zero-based offset page request. */
public record PageRequest(int pageIndex, int pageSize) {

  public PageRequest {
    if (pageIndex < 0) {
      throw new IllegalArgumentException("pageIndex must not be negative");
    }
    if (pageSize <= 0) {
      throw new IllegalArgumentException("pageSize must be positive");
    }
  }

  public static PageRequest page(int pageIndex, int pageSize) {
    return new PageRequest(pageIndex, pageSize);
  }

  long offset() {
    return (long) pageIndex * pageSize;
  }
}
