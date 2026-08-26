package io.skis.query;

import io.skis.jdbc.CompiledQueryPlan;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Shared, bounded LRU cache for value-independent single-table projection plans. */
final class ProjectionPlanCache {

  static final int DEFAULT_MAXIMUM_SIZE = 4096;
  static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofMinutes(30);

  private final int maximumSize;
  private final long expireAfterAccessNanos;
  private final LongSupplier ticker;
  private final LinkedHashMap<ProjectionPlanKey, CacheEntry> plans =
      new LinkedHashMap<>(16, 0.75F, true);
  private long hits;
  private long misses;
  private long evictions;
  private long invalidations;

  ProjectionPlanCache(int maximumSize) {
    this(maximumSize, DEFAULT_EXPIRE_AFTER_ACCESS, System::nanoTime);
  }

  ProjectionPlanCache(int maximumSize, Duration expireAfterAccess) {
    this(maximumSize, expireAfterAccess, System::nanoTime);
  }

  ProjectionPlanCache(int maximumSize, Duration expireAfterAccess, LongSupplier ticker) {
    if (maximumSize < 1) {
      throw new IllegalArgumentException("projection plan cache maximumSize must be positive");
    }
    this.maximumSize = maximumSize;
    this.expireAfterAccessNanos = requirePositiveNanos(expireAfterAccess);
    this.ticker = Objects.requireNonNull(ticker, "ticker");
  }

  synchronized <E, R> CompiledQueryPlan<R, Object> getOrCompile(
      EntityMeta<E> entity,
      Projection<E, R> projection,
      @Nullable PropertyMeta<E, ?> equalityProperty,
      Supplier<CompiledQueryPlan<R, Object>> compiler) {
    Objects.requireNonNull(compiler, "compiler");
    ProjectionPlanKey key = ProjectionPlanKey.of(entity, projection, equalityProperty);
    long now = ticker.getAsLong();
    evictExpired(now);
    CacheEntry existing = plans.get(key);
    if (existing != null) {
      hits++;
      existing.lastAccessNanos = now;
      return cast(existing.plan);
    }
    misses++;
    CompiledQueryPlan<R, Object> compiled = Objects.requireNonNull(compiler.get(), "compiled plan");
    plans.put(key, new CacheEntry(compiled, ticker.getAsLong()));
    evictEldestIfNecessary();
    return compiled;
  }

  synchronized int size() {
    evictExpired(ticker.getAsLong());
    return plans.size();
  }

  synchronized QueryPlanCacheStatistics statistics() {
    evictExpired(ticker.getAsLong());
    return new QueryPlanCacheStatistics(
        hits, misses, evictions, invalidations, plans.size(), maximumSize);
  }

  synchronized void clear() {
    evictExpired(ticker.getAsLong());
    invalidations += plans.size();
    plans.clear();
  }

  synchronized int invalidate(EntityMeta<?> entity) {
    Objects.requireNonNull(entity, "entity");
    evictExpired(ticker.getAsLong());
    int removed = 0;
    Iterator<ProjectionPlanKey> keys = plans.keySet().iterator();
    while (keys.hasNext()) {
      if (keys.next().entity == entity) {
        keys.remove();
        removed++;
      }
    }
    invalidations += removed;
    return removed;
  }

  private void evictEldestIfNecessary() {
    if (plans.size() <= maximumSize) {
      return;
    }
    Iterator<Map.Entry<ProjectionPlanKey, CacheEntry>> entries = plans.entrySet().iterator();
    entries.next();
    entries.remove();
    evictions++;
  }

  private void evictExpired(long now) {
    Iterator<Map.Entry<ProjectionPlanKey, CacheEntry>> entries = plans.entrySet().iterator();
    while (entries.hasNext()) {
      CacheEntry entry = entries.next().getValue();
      if (now - entry.lastAccessNanos < expireAfterAccessNanos) {
        break;
      }
      entries.remove();
      evictions++;
    }
  }

  @SuppressWarnings("unchecked")
  private static <R> CompiledQueryPlan<R, Object> cast(CompiledQueryPlan<?, Object> plan) {
    return (CompiledQueryPlan<R, Object>) plan;
  }

  private static long requirePositiveNanos(Duration duration) {
    Objects.requireNonNull(duration, "expireAfterAccess");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(
          "projection plan cache expireAfterAccess must be positive");
    }
    try {
      return duration.toNanos();
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static final class CacheEntry {

    private final CompiledQueryPlan<?, Object> plan;
    private long lastAccessNanos;

    private CacheEntry(CompiledQueryPlan<?, Object> plan, long lastAccessNanos) {
      this.plan = Objects.requireNonNull(plan, "plan");
      this.lastAccessNanos = lastAccessNanos;
    }
  }

  private static final class ProjectionPlanKey {

    private final EntityMeta<?> entity;
    private final Projection.Mapping<?> mapping;
    private final int[] selectionOrdinals;
    private final int equalityOrdinal;
    private final int hashCode;

    private ProjectionPlanKey(
        EntityMeta<?> entity,
        Projection.Mapping<?> mapping,
        int[] selectionOrdinals,
        int equalityOrdinal) {
      this.entity = entity;
      this.mapping = mapping;
      this.selectionOrdinals = selectionOrdinals;
      this.equalityOrdinal = equalityOrdinal;
      int hash = System.identityHashCode(entity);
      hash = 31 * hash + System.identityHashCode(mapping);
      hash = 31 * hash + Arrays.hashCode(selectionOrdinals);
      this.hashCode = 31 * hash + equalityOrdinal;
    }

    private static <E, R> ProjectionPlanKey of(
        EntityMeta<E> entity,
        Projection<E, R> projection,
        @Nullable PropertyMeta<E, ?> equalityProperty) {
      int[] ordinals = new int[projection.properties().size()];
      for (int index = 0; index < ordinals.length; index++) {
        ordinals[index] = projection.properties().get(index).ordinal();
      }
      int predicateOrdinal = equalityProperty == null ? -1 : equalityProperty.ordinal();
      return new ProjectionPlanKey(
          Objects.requireNonNull(entity, "entity"),
          projection.mapping(),
          ordinals,
          predicateOrdinal);
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || other instanceof ProjectionPlanKey key
              && entity == key.entity
              && mapping == key.mapping
              && equalityOrdinal == key.equalityOrdinal
              && Arrays.equals(selectionOrdinals, key.selectionOrdinals);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }
}
