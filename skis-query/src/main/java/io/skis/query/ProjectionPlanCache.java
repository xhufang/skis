package io.skis.query;

import io.skis.metadata.EntityMeta;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Bounded shared L1 cache for immutable, value-independent query plans.
 *
 * <p>The historical class name is retained until the public query API is reviewed in 0.2.7. Cache
 * hits only read a concurrent map and update per-entry/counter atomics. Compilation, rendering, and
 * waits never run under the maintenance lock. Distinct-key compilation admission is bounded by the
 * configured entry capacity; invalidation removes stale registrations but their owners retain
 * admission until completion, and same-key callers share one registered flight. An active compiler
 * may read cached plans but must not initiate another miss in this cache, even after invalidation.
 */
final class ProjectionPlanCache {

  static final int DEFAULT_MAXIMUM_SIZE = 4096;
  static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofMinutes(30);

  private final int maximumSize;
  private final long expireAfterAccessNanos;
  private final LongSupplier ticker;
  private final ConcurrentMap<QueryPlanKey, CacheEntry> entries = new ConcurrentHashMap<>();
  private final ConcurrentMap<QueryPlanKey, InFlight> inFlight = new ConcurrentHashMap<>();
  private final Set<Thread> activeCompilers = ConcurrentHashMap.newKeySet();
  private final Semaphore compilationAdmissions;
  private final ReentrantLock admissionWaitLock = new ReentrantLock(true);
  private final Condition admissionChanged = admissionWaitLock.newCondition();
  private final ReentrantLock maintenanceLock = new ReentrantLock();
  private final Map<EntityMeta<?>, Long> entityGenerations = new IdentityHashMap<>();
  private final LongAdder hitCount = new LongAdder();
  private final LongAdder missCount = new LongAdder();
  private final LongAdder evictionCount = new LongAdder();
  private final LongAdder invalidationCount = new LongAdder();
  private long globalGeneration;

  ProjectionPlanCache(int maximumSize, Duration expireAfterAccess, LongSupplier ticker) {
    if (maximumSize < 0) {
      throw new IllegalArgumentException("projection plan cache maximumSize must not be negative");
    }
    Objects.requireNonNull(expireAfterAccess, "expireAfterAccess");
    if (expireAfterAccess.isZero() || expireAfterAccess.isNegative()) {
      throw new IllegalArgumentException(
          "projection plan cache expireAfterAccess must be positive");
    }
    this.maximumSize = maximumSize;
    this.expireAfterAccessNanos = toNanosSaturated(expireAfterAccess);
    this.ticker = Objects.requireNonNull(ticker, "ticker");
    this.compilationAdmissions = new Semaphore(maximumSize, true);
  }

  <R> CachedQueryPlan<R> getOrCompile(
      QueryPlanKey key,
      QueryPlanDependencies dependencies,
      Supplier<? extends CachedQueryPlan<R>> compiler) {
    QueryPlanKey requiredKey = Objects.requireNonNull(key, "key");
    QueryPlanDependencies requiredDependencies =
        Objects.requireNonNull(dependencies, "dependencies");
    Supplier<? extends CachedQueryPlan<R>> requiredCompiler =
        Objects.requireNonNull(compiler, "compiler");
    if (maximumSize == 0) {
      return requireCompiled(requiredCompiler);
    }
    requiredKey.requireDependencies(requiredDependencies);

    CachedQueryPlan<R> cached = findLive(requiredKey, requiredDependencies, true);
    if (cached != null) {
      return cached;
    }
    if (activeCompilers.contains(Thread.currentThread())) {
      throw new IllegalStateException("recursive compilation in the same query plan cache");
    }
    return compileOrAwait(requiredKey, requiredDependencies, requiredCompiler);
  }

  QueryPlanCacheStatistics statistics() {
    if (maximumSize == 0) {
      return new QueryPlanCacheStatistics(0, 0, 0, 0, 0, 0);
    }
    return new QueryPlanCacheStatistics(
        hitCount.sum(),
        missCount.sum(),
        evictionCount.sum(),
        invalidationCount.sum(),
        entries.size(),
        maximumSize);
  }

  void clear() {
    if (maximumSize == 0) {
      return;
    }
    maintenanceLock.lock();
    try {
      globalGeneration++;
      inFlight.forEach(this::discardFlight);
      int removed = removeEntries(null);
      invalidationCount.add(removed);
    } finally {
      maintenanceLock.unlock();
    }
  }

  int invalidate(EntityMeta<?> entity) {
    EntityMeta<?> requiredEntity = Objects.requireNonNull(entity, "entity");
    if (maximumSize == 0) {
      return 0;
    }
    maintenanceLock.lock();
    try {
      entityGenerations.put(requiredEntity, entityGeneration(requiredEntity) + 1);
      inFlight.forEach(
          (key, compiling) -> {
            if (compiling.dependencies().contains(requiredEntity)) {
              discardFlight(key, compiling);
            }
          });
      int removed = removeEntries(requiredEntity);
      invalidationCount.add(removed);
      return removed;
    } finally {
      maintenanceLock.unlock();
    }
  }

  private @Nullable <R> CachedQueryPlan<R> findLive(
      QueryPlanKey key, QueryPlanDependencies dependencies, boolean recordHit) {
    while (true) {
      CacheEntry entry = entries.get(key);
      if (entry == null) {
        return null;
      }
      requireSameDependencies(entry.dependencies(), dependencies);
      long now = ticker.getAsLong();
      if (entry.expired(now, expireAfterAccessNanos)) {
        if (entries.remove(key, entry)) {
          evictionCount.increment();
          return null;
        }
        continue;
      }
      if (recordHit) {
        entry.recordAccess(now);
        hitCount.increment();
      }
      return entry.plan();
    }
  }

  private <R> CachedQueryPlan<R> compileOrAwait(
      QueryPlanKey key,
      QueryPlanDependencies dependencies,
      Supplier<? extends CachedQueryPlan<R>> compiler) {
    while (true) {
      // A plan may have been published after this request observed its initial miss, but before it
      // reached the in-flight map. Reuse it without reclassifying the request as a hit.
      CachedQueryPlan<R> published = findLive(key, dependencies, false);
      if (published != null) {
        missCount.increment();
        return published;
      }

      InFlight existing = inFlight.get(key);
      if (existing != null) {
        requireSameDependencies(existing.dependencies(), dependencies);
        if (generationIsNotCurrent(existing.snapshot())) {
          discardFlight(key, existing);
          continue;
        }
        if (existing.owner() == Thread.currentThread()) {
          throw new IllegalStateException("recursive compilation of the same query plan key");
        }
        missCount.increment();
        return await(existing);
      }

      CompilationAdmission admission = acquireCompilationAdmission(key);
      if (admission == AdmissionAction.RETRY_LOOKUP) {
        continue;
      }
      if (admission instanceof InFlight admittedExisting) {
        requireSameDependencies(admittedExisting.dependencies(), dependencies);
        if (generationIsNotCurrent(admittedExisting.snapshot())) {
          discardFlight(key, admittedExisting);
          continue;
        }
        if (admittedExisting.owner() == Thread.currentThread()) {
          throw new IllegalStateException("recursive compilation of the same query plan key");
        }
        missCount.increment();
        return await(admittedExisting);
      }

      boolean admissionTransferred = false;
      try {
        published = findLive(key, dependencies, false);
        if (published != null) {
          missCount.increment();
          return published;
        }

        GenerationSnapshot snapshot;
        InFlight created;
        maintenanceLock.lock();
        try {
          snapshot = generationSnapshotLocked(dependencies);
          created = new InFlight(dependencies, snapshot, Thread.currentThread());
          existing = inFlight.putIfAbsent(key, created);
        } finally {
          maintenanceLock.unlock();
        }
        signalAdmissionChange();
        if (existing != null) {
          requireSameDependencies(existing.dependencies(), dependencies);
          if (generationIsNotCurrent(existing.snapshot())) {
            discardFlight(key, existing);
            continue;
          }
          if (existing.owner() == Thread.currentThread()) {
            throw new IllegalStateException("recursive compilation of the same query plan key");
          }
          missCount.increment();
          return await(existing);
        }

        admissionTransferred = true;
        missCount.increment();

        // A previous flight can publish and remove itself between this request's recheck and its
        // successful putIfAbsent. Once this placeholder is installed, recheck under per-key
        // ownership so that the narrow hand-off window does not trigger a duplicate compilation.
        CachedQueryPlan<R> concurrentlyPublished;
        try {
          concurrentlyPublished = findLive(key, dependencies, false);
        } catch (RuntimeException | Error failure) {
          completeFlight(key, created, FlightOutcome.failure(failure));
          throw failure;
        }
        if (concurrentlyPublished != null) {
          completeFlight(key, created, FlightOutcome.success(concurrentlyPublished));
          return concurrentlyPublished;
        }
        return compileAndPublish(key, created, compiler);
      } finally {
        if (!admissionTransferred) {
          releaseCompilationAdmission();
        }
      }
    }
  }

  private <R> CachedQueryPlan<R> compileAndPublish(
      QueryPlanKey key, InFlight compiling, Supplier<? extends CachedQueryPlan<R>> compiler) {
    try {
      CachedQueryPlan<R> compiled;
      Thread owner = Thread.currentThread();
      activeCompilers.add(owner);
      try {
        compiled = requireCompiled(compiler);
      } finally {
        // Invalidation can remove the joinable flight, but not this active supplier's ownership.
        activeCompilers.remove(owner);
      }
      CachedQueryPlan<R> effective = publish(key, compiling, compiled);
      completeFlight(key, compiling, FlightOutcome.success(effective));
      return effective;
    } catch (RuntimeException | Error failure) {
      completeFlight(key, compiling, FlightOutcome.failure(failure));
      throw failure;
    }
  }

  private CompilationAdmission acquireCompilationAdmission(QueryPlanKey key) {
    try {
      admissionWaitLock.lockInterruptibly();
      try {
        while (true) {
          // A published plan needs no compilation permit, even if another key took the last one.
          // Recheck liveness outside this lock so expiration and dependency checks stay centralized.
          if (entries.containsKey(key)) {
            return AdmissionAction.RETRY_LOOKUP;
          }
          // Wake when a same-key owner registers or a compilation publishes/completes.
          // Registering waiters themselves would recreate the unbounded in-flight map this
          // admission layer is intended to prevent.
          InFlight existing = inFlight.get(key);
          if (existing != null) {
            return existing;
          }
          if (compilationAdmissions.tryAcquire()) {
            return AdmissionAction.ACQUIRED;
          }
          admissionChanged.await();
        }
      } finally {
        admissionWaitLock.unlock();
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted while waiting for query plan compilation admission", failure);
    }
  }

  private void signalAdmissionChange() {
    admissionWaitLock.lock();
    try {
      admissionChanged.signalAll();
    } finally {
      admissionWaitLock.unlock();
    }
  }

  private void releaseCompilationAdmission() {
    admissionWaitLock.lock();
    try {
      compilationAdmissions.release();
      admissionChanged.signalAll();
    } finally {
      admissionWaitLock.unlock();
    }
  }

  private void discardFlight(QueryPlanKey key, InFlight flight) {
    inFlight.remove(key, flight);
  }

  private void completeFlight(QueryPlanKey key, InFlight flight, FlightOutcome outcome) {
    inFlight.remove(key, flight);
    flight.releaseAdmission(this::releaseCompilationAdmission);
    flight.result().complete(outcome);
  }

  private <R> CachedQueryPlan<R> publish(
      QueryPlanKey key, InFlight compiling, CachedQueryPlan<R> compiled) {
    maintenanceLock.lock();
    try {
      if (generationIsNotCurrentLocked(compiling.snapshot())) {
        return compiled;
      }
      long now = ticker.getAsLong();
      CacheEntry existing = entries.get(key);
      if (existing != null) {
        requireSameDependencies(existing.dependencies(), compiling.dependencies());
        if (!existing.expired(now, expireAfterAccessNanos)) {
          return existing.plan();
        }
        if (entries.remove(key, existing)) {
          evictionCount.increment();
        }
      }
      removeExpiredEntries(now);
      while (entries.size() >= maximumSize) {
        evictOldest(now);
      }
      CacheEntry inserted = new CacheEntry(compiled, compiling.dependencies(), now);
      CacheEntry raced = entries.putIfAbsent(key, inserted);
      if (raced == null) {
        return compiled;
      }
      requireSameDependencies(raced.dependencies(), compiling.dependencies());
      return raced.plan();
    } finally {
      maintenanceLock.unlock();
    }
  }

  private void removeExpiredEntries(long now) {
    entries.forEach(
        (key, entry) -> {
          if (entry.expired(now, expireAfterAccessNanos) && entries.remove(key, entry)) {
            evictionCount.increment();
          }
        });
  }

  private void evictOldest(long now) {
    QueryPlanKey oldestKey = null;
    CacheEntry oldestEntry = null;
    long oldestAge = Long.MIN_VALUE;
    for (var candidate : entries.entrySet()) {
      long age = now - candidate.getValue().lastAccessNanos();
      if (oldestEntry == null || age > oldestAge) {
        oldestKey = candidate.getKey();
        oldestEntry = candidate.getValue();
        oldestAge = age;
      }
    }
    if (oldestEntry != null && entries.remove(oldestKey, oldestEntry)) {
      evictionCount.increment();
    }
  }

  private int removeEntries(@Nullable EntityMeta<?> entity) {
    int removed = 0;
    for (var candidate : entries.entrySet()) {
      if ((entity == null || candidate.getValue().dependencies().contains(entity))
          && entries.remove(candidate.getKey(), candidate.getValue())) {
        removed++;
      }
    }
    return removed;
  }

  private GenerationSnapshot generationSnapshotLocked(QueryPlanDependencies dependencies) {
    long[] generations = new long[dependencies.entities().size()];
    for (int index = 0; index < generations.length; index++) {
      generations[index] = entityGeneration(dependencies.entities().get(index));
    }
    return new GenerationSnapshot(dependencies, globalGeneration, generations);
  }

  private boolean generationIsNotCurrent(GenerationSnapshot snapshot) {
    maintenanceLock.lock();
    try {
      return generationIsNotCurrentLocked(snapshot);
    } finally {
      maintenanceLock.unlock();
    }
  }

  private boolean generationIsNotCurrentLocked(GenerationSnapshot snapshot) {
    if (snapshot.globalGeneration() != globalGeneration) {
      return true;
    }
    for (int index = 0; index < snapshot.entityGenerations.length; index++) {
      if (snapshot.entityGenerations[index]
          != entityGeneration(snapshot.dependencies().entities().get(index))) {
        return true;
      }
    }
    return false;
  }

  private long entityGeneration(EntityMeta<?> entity) {
    return entityGenerations.getOrDefault(entity, 0L);
  }

  private static void requireSameDependencies(
      QueryPlanDependencies first, QueryPlanDependencies second) {
    if (!first.sameEntities(second)) {
      throw new IllegalStateException(
          "one query plan key resolved to different entity dependencies: "
              + first
              + " and "
              + second);
    }
  }

  private static <R> CachedQueryPlan<R> requireCompiled(
      Supplier<? extends CachedQueryPlan<R>> compiler) {
    return Objects.requireNonNull(compiler.get(), "compiled query plan");
  }

  @SuppressWarnings("unchecked")
  private static <R> CachedQueryPlan<R> await(InFlight compiling) {
    return (CachedQueryPlan<R>) compiling.result().join().valueOrThrow();
  }

  @SuppressWarnings("unchecked")
  private static <R> CachedQueryPlan<R> cast(CachedQueryPlan<?> plan) {
    return (CachedQueryPlan<R>) plan;
  }

  private static long toNanosSaturated(Duration duration) {
    try {
      return duration.toNanos();
    } catch (ArithmeticException ignored) {
      return Long.MAX_VALUE;
    }
  }

  private record GenerationSnapshot(
      QueryPlanDependencies dependencies, long globalGeneration, long[] entityGenerations) {

    private GenerationSnapshot {
      Objects.requireNonNull(dependencies, "dependencies");
      entityGenerations = entityGenerations.clone();
    }
  }

  private sealed interface CompilationAdmission permits AdmissionAction, InFlight {}

  private enum AdmissionAction implements CompilationAdmission {
    ACQUIRED,
    RETRY_LOOKUP
  }

  private record InFlight(
      QueryPlanDependencies dependencies,
      GenerationSnapshot snapshot,
      Thread owner,
      CompletableFuture<FlightOutcome> result,
      AtomicBoolean admissionReleased)
      implements CompilationAdmission {

    private InFlight(
        QueryPlanDependencies dependencies, GenerationSnapshot snapshot, Thread owner) {
      this(
          Objects.requireNonNull(dependencies, "dependencies"),
          Objects.requireNonNull(snapshot, "snapshot"),
          Objects.requireNonNull(owner, "owner"),
          new CompletableFuture<>(),
          new AtomicBoolean());
    }

    private void releaseAdmission(Runnable release) {
      if (admissionReleased.compareAndSet(false, true)) {
        release.run();
      }
    }
  }

  private record FlightOutcome(
      @Nullable CachedQueryPlan<?> value, @Nullable Throwable failure) {

    private FlightOutcome {
      if ((value == null) == (failure == null)) {
        throw new IllegalArgumentException(
            "a query-plan flight outcome requires exactly one value or failure");
      }
    }

    private static FlightOutcome success(CachedQueryPlan<?> value) {
      return new FlightOutcome(Objects.requireNonNull(value, "value"), null);
    }

    private static FlightOutcome failure(Throwable failure) {
      return new FlightOutcome(null, Objects.requireNonNull(failure, "failure"));
    }

    private CachedQueryPlan<?> valueOrThrow() {
      if (failure instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      if (failure instanceof Error error) {
        throw error;
      }
      if (failure != null) {
        throw new IllegalStateException("query plan compilation failed", failure);
      }
      return Objects.requireNonNull(value, "value");
    }
  }

  private static final class CacheEntry {

    private final CachedQueryPlan<?> plan;
    private final QueryPlanDependencies dependencies;
    private final AtomicLong lastAccessNanos;

    private CacheEntry(
        CachedQueryPlan<?> plan, QueryPlanDependencies dependencies, long lastAccessNanos) {
      this.plan = Objects.requireNonNull(plan, "plan");
      this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
      this.lastAccessNanos = new AtomicLong(lastAccessNanos);
    }

    private <R> CachedQueryPlan<R> plan() {
      return cast(plan);
    }

    private QueryPlanDependencies dependencies() {
      return dependencies;
    }

    private long lastAccessNanos() {
      return lastAccessNanos.get();
    }

    private boolean expired(long now, long expirationNanos) {
      return now - lastAccessNanos() >= expirationNanos;
    }

    private void recordAccess(long now) {
      lastAccessNanos.accumulateAndGet(now, Math::max);
    }
  }
}
