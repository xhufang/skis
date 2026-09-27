package io.skis.query;

/**
 * Immutable snapshot of the shared L1 query-plan cache.
 *
 * <p>A {@code maximumSize} of zero means that the shared L1 cache is disabled. That snapshot has a
 * zero size and no hit, miss, eviction, or invalidation activity.
 */
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
    if (size < 0 || maximumSize < 0 || size > maximumSize) {
      throw new IllegalArgumentException("invalid query plan cache size snapshot");
    }
    if (maximumSize == 0
        && (hitCount != 0
            || missCount != 0
            || evictionCount != 0
            || invalidationCount != 0)) {
      throw new IllegalArgumentException(
          "disabled query plan caches must not report activity counters");
    }
  }
}
