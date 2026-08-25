package io.skis.query;

/** Immutable snapshot of the shared dynamic query-plan cache. */
public record QueryPlanCacheStatistics(
    long hitCount,
    long missCount,
    long evictionCount,
    long invalidationCount,
    int size,
    int maximumSize) {

  public QueryPlanCacheStatistics {
    if (hitCount < 0 || missCount < 0 || evictionCount < 0 || invalidationCount < 0) {
      throw new IllegalArgumentException("query plan cache counters must not be negative");
    }
    if (size < 0 || maximumSize < 1 || size > maximumSize) {
      throw new IllegalArgumentException("invalid query plan cache size snapshot");
    }
  }
}
