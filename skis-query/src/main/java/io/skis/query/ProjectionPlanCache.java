package io.skis.query;

import io.skis.metadata.EntityMeta;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Cache-governance state retained for the public 0.2.x diagnostics API.
 *
 * <p>Generated {@link ProjectionSelection} plans are deliberately query-local in 0.2.4. This
 * holder therefore reports no shared entries until T05 replaces it with the general structural
 * plan cache; it does not preserve the removed entity-bound projection cache key.
 */
final class ProjectionPlanCache {

  static final int DEFAULT_MAXIMUM_SIZE = 4096;
  static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofMinutes(30);

  private final int maximumSize;

  ProjectionPlanCache(int maximumSize, Duration expireAfterAccess, LongSupplier ticker) {
    if (maximumSize < 0) {
      throw new IllegalArgumentException(
          "projection plan cache maximumSize must not be negative");
    }
    Objects.requireNonNull(expireAfterAccess, "expireAfterAccess");
    if (expireAfterAccess.isZero() || expireAfterAccess.isNegative()) {
      throw new IllegalArgumentException("projection plan cache expireAfterAccess must be positive");
    }
    Objects.requireNonNull(ticker, "ticker");
    this.maximumSize = maximumSize;
  }

  synchronized QueryPlanCacheStatistics statistics() {
    return new QueryPlanCacheStatistics(0, 0, 0, 0, 0, maximumSize);
  }

  synchronized void clear() {
    // Shared entries are not populated until T05; query-local plans are not owned here.
  }

  synchronized int invalidate(EntityMeta<?> entity) {
    Objects.requireNonNull(entity, "entity");
    return 0;
  }
}
