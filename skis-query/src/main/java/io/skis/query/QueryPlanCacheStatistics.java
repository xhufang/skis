package io.skis.query;

/**
 * Immutable snapshot of the shared L1 query-plan cache.
 *
 * <p>Activity counters are cumulative for the lifetime of one plan catalog. Reading a snapshot does
 * not run expiration maintenance. A {@code maximumSize} of zero means that L1 is disabled; that
 * snapshot has a zero size and no hit, miss, eviction, or invalidation activity.
 *
 * @param hitCount requests whose L1 lookup found a live cached plan
 * @param missCount requests whose L1 lookup did not find a live plan and then either registered or
 *     joined a flight, including callers that reused a plan published in the hand-off window
 * @param evictionCount entries automatically removed by capacity or idle expiration
 * @param invalidationCount entries actually removed by clear or entity invalidation
 * @param size entries present when the snapshot was read; expired entries remain until normal cache
 *     maintenance observes them
 * @param maximumSize configured entry capacity, or zero when L1 is disabled
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
