package io.skis.mutation;

/** Cumulative activity and current occupancy of one executor's shared dynamic write-plan cache. */
public record MutationPlanCacheStatistics(
    long hitCount,
    long missCount,
    long evictionCount,
    long invalidationCount,
    int size,
    int maximumSize) {
  public MutationPlanCacheStatistics {
    if (hitCount < 0
        || missCount < 0
        || evictionCount < 0
        || invalidationCount < 0
        || size < 0
        || maximumSize < 0
        || size > maximumSize) {
      throw new IllegalArgumentException("invalid mutation plan cache statistics");
    }
    if (maximumSize == 0
        && (hitCount != 0 || missCount != 0 || evictionCount != 0 || invalidationCount != 0)) {
      throw new IllegalArgumentException("disabled mutation cache cannot report activity");
    }
  }
}
