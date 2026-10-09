package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.RenderedSql;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardSqlRenderer;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mapping.JdbcCodecs;
import io.skis.mapping.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.ResolvedStructureKey;
import io.skis.sql.ast.SelectStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class ProjectionPlanCacheTest {

  private static final PropertyMeta<Pet, Long> PET_ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "shelter", "pet"),
          List.of(PET_ID),
          new PrimaryKeyMeta<>(List.of(PET_ID)),
          false);
  private static final PropertyMeta<Owner, Long> OWNER_ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final EntityMeta<Owner> OWNER =
      EntityMeta.simple(
          Owner.class,
          new TableMeta("", "shelter", "owner"),
          List.of(OWNER_ID),
          new PrimaryKeyMeta<>(List.of(OWNER_ID)),
          false);
  private static final PetTable PET_TABLE = new PetTable();
  private static final EntityRuntimeRegistry RUNTIME_REGISTRY =
      EntityRuntimeRegistry.of(List.of(petRuntimeModel()));
  private static final QueryPlanKey.IdentityScope IDENTITIES =
      new QueryPlanKey.IdentityScope(RUNTIME_REGISTRY);
  private static final QueryPlanKey.ResultShape RESULT_SHAPE =
      SelectedResult.entity(PET_TABLE).planKeyResultShape(IDENTITIES).orElseThrow();
  private static final DialectCapabilities TEST_CAPABILITIES =
      DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
  private static final QueryPlanKey.DialectIdentity DIALECT =
      new QueryPlanKey.DialectIdentity(
          "test", TEST_CAPABILITIES, TEST_CAPABILITIES.version());

  @Test
  void recordsRealHitsMissesAndSize() {
    ProjectionPlanCache cache = cache(4, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey key = key("pet-by-name");
    QueryPlanDependencies dependencies = dependencies(PET);
    AtomicInteger compiles = new AtomicInteger();
    CachedQueryPlan<Pet> compiled = plan("SELECT first");

    CachedQueryPlan<Pet> first =
        cache.getOrCompile(
            key,
            dependencies,
            () -> {
              compiles.incrementAndGet();
              return compiled;
            });
    CachedQueryPlan<Pet> second =
        cache.getOrCompile(
            key,
            dependencies,
            () -> {
              throw new AssertionError("cache hit must not compile");
            });

    assertSame(compiled, first);
    assertSame(first, second);
    assertEquals(1, compiles.get());
    assertEquals(new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 4), cache.statistics());
  }

  @Test
  void catalogOwnsStatisticsClearAndEntityInvalidation() {
    QueryPlanCatalog catalog =
        new QueryPlanCatalog(RUNTIME_REGISTRY, testDialect(), 2, Duration.ofMinutes(1));
    QueryOperations operations = QueryTestSupport.operations(catalog);
    // The alias routes these fresh queries through the ordinary assembler/resolver path.
    CompiledQueryPlan<Pet, Object> compiled = catalogQuery(operations);
    assertSame(compiled, catalogQuery(operations));
    assertEquals(
        new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 2),
        catalog.projectionPlanCacheStatistics());
    assertEquals(1, catalog.invalidateProjectionPlans(PET));
    assertEquals(
        new QueryPlanCacheStatistics(1, 1, 0, 1, 0, 2),
        catalog.projectionPlanCacheStatistics());
    assertNotSame(compiled, catalogQuery(operations));
    catalog.clearProjectionPlans();
    assertEquals(
        new QueryPlanCacheStatistics(1, 2, 0, 2, 0, 2),
        catalog.projectionPlanCacheStatistics());
    assertEquals(0, catalog.invalidateProjectionPlans(OWNER));
    assertEquals(
        new QueryPlanCacheStatistics(1, 2, 0, 2, 0, 2),
        catalog.projectionPlanCacheStatistics());
  }

  private static CompiledQueryPlan<Pet, Object> catalogQuery(QueryOperations operations) {
    PetTable table = new PetTable(Identifier.of("p"));
    return ((DefaultSelectQuery<Pet, Pet>) operations.selectFrom(table))
        .compilation(QueryPagination.None.INSTANCE).plan();
  }

  @Test
  void zeroCapacityBypassesEveryL1Activity() {
    ProjectionPlanCache cache =
        new ProjectionPlanCache(
            0,
            Duration.ofMinutes(30),
            () -> {
              throw new AssertionError("disabled L1 must not read its ticker");
            });
    AtomicInteger compiles = new AtomicInteger();

    CachedQueryPlan<Pet> first =
        cache.getOrCompile(
            key("disabled"),
            dependencies(PET),
            () -> plan("SELECT disabled_" + compiles.incrementAndGet()));
    CachedQueryPlan<Pet> second =
        cache.getOrCompile(
            key("disabled"),
            dependencies(PET),
            () -> plan("SELECT disabled_" + compiles.incrementAndGet()));

    assertNotSame(first, second);
    assertEquals(2, compiles.get());
    cache.clear();
    assertEquals(0, cache.invalidate(PET));
    assertEquals(new QueryPlanCacheStatistics(0, 0, 0, 0, 0, 0), cache.statistics());
  }

  @Test
  void zeroCapacityDoesNotSingleFlightConcurrentRequests() throws Exception {
    ProjectionPlanCache cache = cache(0, Duration.ofMinutes(30), new AtomicLong());
    CountDownLatch bothCompiling = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CachedQueryPlan<Pet>> first =
          executor.submit(
              () ->
                  cache.getOrCompile(
                      key("disabled-concurrent"),
                      dependencies(PET),
                      () -> blockedPlan("SELECT disabled_first", bothCompiling, release)));
      Future<CachedQueryPlan<Pet>> second =
          executor.submit(
              () ->
                  cache.getOrCompile(
                      key("disabled-concurrent"),
                      dependencies(PET),
                      () -> blockedPlan("SELECT disabled_second", bothCompiling, release)));

      assertTrue(bothCompiling.await(5, TimeUnit.SECONDS));
      release.countDown();
      assertNotSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }

    assertEquals(new QueryPlanCacheStatistics(0, 0, 0, 0, 0, 0), cache.statistics());
  }

  @Test
  void evictsTheLeastRecentlyAccessedEntryWithoutLockingHits() {
    AtomicLong ticker = new AtomicLong();
    ProjectionPlanCache cache = cache(2, Duration.ofMinutes(1), ticker);
    QueryPlanDependencies dependencies = dependencies(PET);
    QueryPlanKey firstKey = key("first");
    QueryPlanKey secondKey = key("second");
    QueryPlanKey thirdKey = key("third");
    CachedQueryPlan<Pet> first = plan("SELECT first");
    CachedQueryPlan<Pet> second = plan("SELECT second");

    cache.getOrCompile(firstKey, dependencies, () -> first);
    ticker.incrementAndGet();
    cache.getOrCompile(secondKey, dependencies, () -> second);
    ticker.incrementAndGet();
    assertSame(
        first,
        cache.getOrCompile(
            firstKey,
            dependencies,
            () -> {
              throw new AssertionError("recent entry must hit");
            }));
    ticker.incrementAndGet();
    cache.getOrCompile(thirdKey, dependencies, () -> plan("SELECT third"));

    assertEquals(new QueryPlanCacheStatistics(1, 3, 1, 0, 2, 2), cache.statistics());
    CachedQueryPlan<Pet> recompiledSecond =
        cache.getOrCompile(secondKey, dependencies, () -> plan("SELECT second_again"));
    assertNotSame(second, recompiledSecond);
    assertEquals(new QueryPlanCacheStatistics(1, 4, 2, 0, 2, 2), cache.statistics());
  }

  @Test
  void expiresAfterIdleAccessAndStatisticsDoNotRunMaintenance() {
    AtomicLong ticker = new AtomicLong();
    ProjectionPlanCache cache = cache(2, Duration.ofNanos(10), ticker);
    QueryPlanKey key = key("expiring");
    QueryPlanDependencies dependencies = dependencies(PET);
    CachedQueryPlan<Pet> first = plan("SELECT expiring_first");

    cache.getOrCompile(key, dependencies, () -> first);
    ticker.set(100);
    assertEquals(new QueryPlanCacheStatistics(0, 1, 0, 0, 1, 2), cache.statistics());
    CachedQueryPlan<Pet> second =
        cache.getOrCompile(key, dependencies, () -> plan("SELECT expiring_second"));

    assertNotSame(first, second);
    assertEquals(new QueryPlanCacheStatistics(0, 2, 1, 0, 1, 2), cache.statistics());
    ticker.set(109);
    assertSame(
        second,
        cache.getOrCompile(
            key,
            dependencies,
            () -> {
              throw new AssertionError("access inside idle window must hit");
            }));
    ticker.set(118);
    assertSame(
        second,
        cache.getOrCompile(
            key,
            dependencies,
            () -> {
              throw new AssertionError("a hit must refresh idle expiration");
            }));
  }

  @Test
  void clearAndEntityInvalidationCountOnlyActuallyRemovedEntries() {
    ProjectionPlanCache cache = cache(4, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey petKey = key("pet");
    QueryPlanKey ownerKey = key("owner", OWNER);
    QueryPlanKey joinedKey = key("pet-owner", PET, OWNER);
    CachedQueryPlan<Pet> ownerPlan = plan("SELECT owner");

    cache.getOrCompile(petKey, dependencies(PET), () -> plan("SELECT pet"));
    cache.getOrCompile(ownerKey, dependencies(OWNER), () -> ownerPlan);
    cache.getOrCompile(joinedKey, dependencies(PET, OWNER), () -> plan("SELECT joined"));

    assertEquals(2, cache.invalidate(PET));
    assertEquals(0, cache.invalidate(PET));
    assertSame(
        ownerPlan,
        cache.getOrCompile(
            ownerKey,
            dependencies(OWNER),
            () -> {
              throw new AssertionError("unrelated entity invalidation must preserve the plan");
            }));
    cache.clear();
    cache.clear();

    assertEquals(new QueryPlanCacheStatistics(1, 3, 0, 3, 0, 4), cache.statistics());
  }

  @Test
  void coordinatesOneCompilationPerKeyAndCountsEveryConcurrentMiss() throws Exception {
    int callers = 8;
    ProjectionPlanCache cache = cache(1, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey key = key("single-flight");
    QueryPlanDependencies dependencies = dependencies(PET);
    CachedQueryPlan<Pet> compiled = plan("SELECT single_flight");
    AtomicInteger compiles = new AtomicInteger();
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch compileStarted = new CountDownLatch(1);
    CountDownLatch releaseCompile = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(callers);
    try {
      List<Future<CachedQueryPlan<Pet>>> futures = new ArrayList<>();
      for (int index = 0; index < callers; index++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  return cache.getOrCompile(
                      key,
                      dependencies,
                      () -> {
                        compiles.incrementAndGet();
                        compileStarted.countDown();
                        awaitUnchecked(releaseCompile);
                        return compiled;
                      });
                }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertTrue(compileStarted.await(5, TimeUnit.SECONDS));
      awaitMissCount(cache, callers);
      releaseCompile.countDown();

      for (Future<CachedQueryPlan<Pet>> future : futures) {
        assertSame(compiled, future.get(5, TimeUnit.SECONDS));
      }
    } finally {
      releaseCompile.countDown();
      executor.shutdownNow();
    }

    assertEquals(1, compiles.get());
    assertEquals(
        new QueryPlanCacheStatistics(0, callers, 0, 0, 1, 1), cache.statistics());
  }

  @Test
  void compilesDifferentKeysConcurrently() throws Exception {
    ProjectionPlanCache cache = cache(4, Duration.ofMinutes(1), new AtomicLong());
    CountDownLatch bothStarted = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CachedQueryPlan<Pet>> first =
          executor.submit(
              () ->
                  cache.getOrCompile(
                      key("parallel-first"),
                      dependencies(PET),
                      () -> blockedPlan("SELECT parallel_first", bothStarted, release)));
      Future<CachedQueryPlan<Pet>> second =
          executor.submit(
              () ->
                  cache.getOrCompile(
                      key("parallel-second"),
                      dependencies(PET),
                      () -> blockedPlan("SELECT parallel_second", bothStarted, release)));

      assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
      release.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }

    assertEquals(new QueryPlanCacheStatistics(0, 2, 0, 0, 2, 4), cache.statistics());
  }

  @Test
  void boundsConcurrentDistinctKeyCompilationsAndPublishedEntries() throws Exception {
    int callers = 12;
    int capacity = 3;
    ProjectionPlanCache cache = cache(capacity, Duration.ofMinutes(1), new AtomicLong());
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger activeCompilations = new AtomicInteger();
    AtomicInteger maximumActiveCompilations = new AtomicInteger();
    CountDownLatch admitted = new CountDownLatch(capacity);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(callers);
    try {
      List<Future<CachedQueryPlan<Pet>>> futures = new ArrayList<>();
      for (int index = 0; index < callers; index++) {
        int keyOrdinal = index;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  return cache.getOrCompile(
                      key("capacity-" + keyOrdinal),
                      dependencies(PET),
                      () -> {
                        int active = activeCompilations.incrementAndGet();
                        maximumActiveCompilations.accumulateAndGet(active, Math::max);
                        admitted.countDown();
                        try {
                          awaitUnchecked(release);
                          return plan("SELECT capacity_" + keyOrdinal);
                        } finally {
                          activeCompilations.decrementAndGet();
                        }
                      });
                }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertTrue(admitted.await(5, TimeUnit.SECONDS));
      assertEquals(capacity, activeCompilations.get());
      release.countDown();
      for (Future<CachedQueryPlan<Pet>> future : futures) {
        future.get(5, TimeUnit.SECONDS);
      }
    } finally {
      release.countDown();
      executor.shutdownNow();
    }

    assertTrue(maximumActiveCompilations.get() <= capacity);
    assertEquals(
        new QueryPlanCacheStatistics(0, callers, callers - capacity, 0, capacity, capacity),
        cache.statistics());
  }

  @Test
  void invalidationDoesNotReturnAdmissionBeforeStaleOwnersFinish() throws Exception {
    int capacity = 2;
    ProjectionPlanCache cache = cache(capacity, Duration.ofMinutes(1), new AtomicLong());
    CountDownLatch staleStarted = new CountDownLatch(capacity);
    CountDownLatch releaseStale = new CountDownLatch(1);
    CountDownLatch freshEntered = new CountDownLatch(1);
    CountDownLatch freshStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(capacity + 1);
    try {
      List<Future<CachedQueryPlan<Pet>>> stale = new ArrayList<>();
      for (int index = 0; index < capacity; index++) {
        int keyOrdinal = index;
        stale.add(
            executor.submit(
                () ->
                    cache.getOrCompile(
                        key("stale-admission-" + keyOrdinal),
                        dependencies(PET),
                        () ->
                            blockedPlan(
                                "SELECT stale_admission_" + keyOrdinal,
                                staleStarted,
                                releaseStale))));
      }
      assertTrue(staleStarted.await(5, TimeUnit.SECONDS));
      cache.clear();

      Future<CachedQueryPlan<Pet>> fresh =
          executor.submit(
              () -> {
                freshEntered.countDown();
                return cache.getOrCompile(
                    key("fresh-admission"),
                    dependencies(PET),
                    () -> {
                      freshStarted.countDown();
                      return plan("SELECT fresh_admission");
                    });
              });
      assertTrue(freshEntered.await(5, TimeUnit.SECONDS));
      assertFalse(freshStarted.await(100, TimeUnit.MILLISECONDS));

      releaseStale.countDown();
      for (Future<CachedQueryPlan<Pet>> future : stale) {
        future.get(5, TimeUnit.SECONDS);
      }
      assertTrue(freshStarted.await(5, TimeUnit.SECONDS));
      assertEquals("SELECT fresh_admission", fresh.get(5, TimeUnit.SECONDS).plan().sql());
    } finally {
      releaseStale.countDown();
      executor.shutdownNow();
    }

    assertEquals(new QueryPlanCacheStatistics(0, 3, 0, 0, 1, 2), cache.statistics());
  }

  @Test
  void sharesCompilationFailureWithoutCachingItAndAllowsRetry() throws Exception {
    ProjectionPlanCache cache = cache(1, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey key = key("failure");
    QueryPlanDependencies dependencies = dependencies(PET);
    IllegalStateException expected = new IllegalStateException("expected compilation failure");
    AtomicInteger compiles = new AtomicInteger();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch compileStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Throwable>> failures = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        failures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  try {
                    cache.getOrCompile(
                        key,
                        dependencies,
                        () -> {
                          compiles.incrementAndGet();
                          compileStarted.countDown();
                          awaitUnchecked(release);
                          throw expected;
                        });
                    return new AssertionError("compilation unexpectedly succeeded");
                  } catch (RuntimeException | Error failure) {
                    return failure;
                  }
                }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertTrue(compileStarted.await(5, TimeUnit.SECONDS));
      awaitMissCount(cache, 2);
      release.countDown();

      assertSame(expected, failures.get(0).get(5, TimeUnit.SECONDS));
      assertSame(expected, failures.get(1).get(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }

    assertEquals(new QueryPlanCacheStatistics(0, 2, 0, 0, 0, 1), cache.statistics());
    CachedQueryPlan<Pet> recovered =
        cache.getOrCompile(
            key,
            dependencies,
            () -> {
              compiles.incrementAndGet();
              return plan("SELECT recovered");
            });
    assertEquals("SELECT recovered", recovered.plan().sql());
    assertEquals(2, compiles.get());
    assertEquals(new QueryPlanCacheStatistics(0, 3, 0, 0, 1, 1), cache.statistics());
  }

  @Test
  void preservesACompilerCompletionExceptionForEveryFlightParticipant() throws Exception {
    ProjectionPlanCache cache = cache(4, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey key = key("completion-failure");
    QueryPlanDependencies dependencies = dependencies(PET);
    CompletionException expected =
        new CompletionException(new IllegalArgumentException("compiler completion failure"));
    CountDownLatch compileStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Throwable> owner =
          executor.submit(
              () ->
                  captureFailure(
                      () ->
                          cache.getOrCompile(
                              key,
                              dependencies,
                              () -> {
                                compileStarted.countDown();
                                awaitUnchecked(release);
                                throw expected;
                              })));
      assertTrue(compileStarted.await(5, TimeUnit.SECONDS));
      Future<Throwable> waiter =
          executor.submit(
              () ->
                  captureFailure(
                      () ->
                          cache.getOrCompile(
                              key,
                              dependencies,
                              () -> {
                                throw new AssertionError("waiter must not compile");
                              })));
      awaitMissCount(cache, 2);
      release.countDown();

      assertSame(expected, owner.get(5, TimeUnit.SECONDS));
      assertSame(expected, waiter.get(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void preservesACompilerErrorForEveryFlightParticipant() throws Exception {
    ProjectionPlanCache cache = cache(4, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey key = key("error-failure");
    QueryPlanDependencies dependencies = dependencies(PET);
    AssertionError expected = new AssertionError("compiler error");
    CountDownLatch compileStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Throwable> owner =
          executor.submit(
              () ->
                  captureFailure(
                      () ->
                          cache.getOrCompile(
                              key,
                              dependencies,
                              () -> {
                                compileStarted.countDown();
                                awaitUnchecked(release);
                                throw expected;
                              })));
      assertTrue(compileStarted.await(5, TimeUnit.SECONDS));
      Future<Throwable> waiter =
          executor.submit(
              () ->
                  captureFailure(
                      () ->
                          cache.getOrCompile(
                              key,
                              dependencies,
                              () -> {
                                throw new AssertionError("waiter must not compile");
                              })));
      awaitMissCount(cache, 2);
      release.countDown();

      assertSame(expected, owner.get(5, TimeUnit.SECONDS));
      assertSame(expected, waiter.get(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }

    assertEquals(new QueryPlanCacheStatistics(0, 2, 0, 0, 0, 4), cache.statistics());
  }

  @Test
  void rejectsRecursiveCompilationOfTheSameKeyWithoutWaitingOnItself() {
    ProjectionPlanCache cache = cache(2, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey key = key("recursive");
    QueryPlanDependencies dependencies = dependencies(PET);

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                cache.getOrCompile(
                    key,
                    dependencies,
                    () -> cache.getOrCompile(key, dependencies, () -> plan("SELECT nested"))));

    assertTrue(failure.getMessage().contains("recursive compilation"));
  }

  @Test
  void rejectsNestedMissesAfterInvalidationAndForDifferentKeysAtCapacityOne() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      for (String operation : List.of("same-key", "clear", "invalidate", "different-key")) {
        ProjectionPlanCache cache = cache(1, Duration.ofMinutes(1), new AtomicLong());
        QueryPlanKey outer = key("outer");
        QueryPlanKey nested = operation.equals("different-key") ? key("nested") : outer;
        AtomicInteger nestedCompiles = new AtomicInteger();
        Future<Throwable> failure =
            executor.submit(
                () ->
                    captureFailure(
                        () ->
                            cache.getOrCompile(
                                outer,
                                dependencies(PET),
                                () -> {
                                  if (operation.equals("clear")) {
                                    cache.clear();
                                  } else if (operation.equals("invalidate")) {
                                    cache.invalidate(PET);
                                  }
                                  return cache.getOrCompile(
                                      nested,
                                      dependencies(PET),
                                      () -> {
                                        nestedCompiles.incrementAndGet();
                                        return plan("SELECT nested");
                                      });
                                })));
        Throwable observed = failure.get(5, TimeUnit.SECONDS);
        assertTrue(observed instanceof IllegalStateException, operation);
        assertTrue(observed.getMessage().contains("recursive compilation"), operation);
        assertEquals(0, nestedCompiles.get(), operation);
        // Reuse the same worker to check both admission release and active-owner cleanup.
        CachedQueryPlan<Pet> recovered =
            executor.submit(
                    () -> cache.getOrCompile(outer, dependencies(PET), () -> plan("SELECT retry")))
                .get(5, TimeUnit.SECONDS);
        assertEquals("SELECT retry", recovered.plan().sql());
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void allowsAnActiveCompilerToReadAnAlreadyCachedPlan() {
    ProjectionPlanCache cache = cache(1, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey readyKey = key("ready");
    CachedQueryPlan<Pet> ready =
        cache.getOrCompile(readyKey, dependencies(PET), () -> plan("SELECT ready"));

    CachedQueryPlan<Pet> outer =
        cache.getOrCompile(
            key("outer"),
            dependencies(PET),
            () ->
                cache.getOrCompile(
                    readyKey,
                    dependencies(PET),
                    () -> {
                      throw new AssertionError("nested cache hit must not compile");
                    }));

    assertSame(ready, outer);
    assertEquals(1, cache.statistics().hitCount());
  }

  @Test
  void admissionWaiterReadsPublishedPlanWhileAnUnrelatedCompilerHoldsTheOnlyPermit()
      throws Exception {
    ProjectionPlanCache cache = cache(1, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey targetKey = key("published-before-admission");
    var lockField = ProjectionPlanCache.class.getDeclaredField("admissionWaitLock");
    lockField.setAccessible(true);
    ReentrantLock admissionLock = (ReentrantLock) lockField.get(cache);
    AtomicReference<Thread> waitingThread = new AtomicReference<>();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    admissionLock.lock();
    try {
      Future<CachedQueryPlan<Pet>> waiter =
          executor.submit(
              () -> {
                waitingThread.set(Thread.currentThread());
                return cache.getOrCompile(
                    targetKey,
                    dependencies(PET),
                    () -> {
                      throw new AssertionError("published target must be reused");
                    });
              });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while ((waitingThread.get() == null || !admissionLock.hasQueuedThread(waitingThread.get()))
          && System.nanoTime() < deadline) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
      }
      assertTrue(waitingThread.get() != null && admissionLock.hasQueuedThread(waitingThread.get()));
      // Only the test holds this gate: the waiter has missed, but has not entered admission yet.
      // This thread can reenter the lock to publish K and then start the unrelated compiler J.
      CachedQueryPlan<Pet> target =
          cache.getOrCompile(targetKey, dependencies(PET), () -> plan("SELECT target"));
      cache.getOrCompile(
          key("unrelated"),
          dependencies(PET),
          () -> {
            admissionLock.unlock();
            assertSame(target, assertDoesNotThrow(() -> waiter.get(5, TimeUnit.SECONDS)));
            return plan("SELECT unrelated");
          });
      assertEquals(3, cache.statistics().missCount());
      assertEquals(0, cache.statistics().hitCount());
    } finally {
      if (admissionLock.isHeldByCurrentThread()) {
        admissionLock.unlock();
      }
      executor.shutdownNow();
    }
  }

  @Test
  void clearDuringCompilationPreventsTheOldGenerationFromBeingPublished() throws Exception {
    ProjectionPlanCache cache = cache(4, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey key = key("clear-race");
    QueryPlanDependencies dependencies = dependencies(PET);
    CountDownLatch staleStarted = new CountDownLatch(1);
    CountDownLatch releaseStale = new CountDownLatch(1);
    CountDownLatch freshStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CachedQueryPlan<Pet> stale = plan("SELECT stale_after_clear");
    CachedQueryPlan<Pet> fresh = plan("SELECT fresh_after_clear");
    try {
      Future<CachedQueryPlan<Pet>> compiling =
          executor.submit(
              () ->
                  cache.getOrCompile(
                      key,
                      dependencies,
                      () -> {
                        staleStarted.countDown();
                        awaitUnchecked(releaseStale);
                        return stale;
                      }));
      assertTrue(staleStarted.await(5, TimeUnit.SECONDS));
      cache.clear();
      Future<CachedQueryPlan<Pet>> afterClear =
          executor.submit(
              () ->
                  cache.getOrCompile(
                      key,
                      dependencies,
                      () -> {
                        freshStarted.countDown();
                        return fresh;
                      }));
      assertTrue(freshStarted.await(5, TimeUnit.SECONDS));
      assertSame(fresh, afterClear.get(5, TimeUnit.SECONDS));
      releaseStale.countDown();
      assertSame(stale, compiling.get(5, TimeUnit.SECONDS));
    } finally {
      releaseStale.countDown();
      executor.shutdownNow();
    }

    assertSame(
        fresh,
        cache.getOrCompile(
            key,
            dependencies,
            () -> {
              throw new AssertionError("old generation must not replace the fresh plan");
            }));
    assertEquals(new QueryPlanCacheStatistics(1, 2, 0, 0, 1, 4), cache.statistics());
  }

  @Test
  void clearSeparatesANewGenerationFromOldOwnerAndWaiterFailure() throws Exception {
    ProjectionPlanCache cache = cache(4, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey key = key("clear-failed-flight");
    QueryPlanDependencies dependencies = dependencies(PET);
    IllegalStateException staleFailure = new IllegalStateException("stale generation failure");
    CachedQueryPlan<Pet> fresh = plan("SELECT fresh_after_failed_flight");
    CountDownLatch staleStarted = new CountDownLatch(1);
    CountDownLatch releaseStale = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Throwable> staleOwner =
          executor.submit(
              () ->
                  captureFailure(
                      () ->
                          cache.getOrCompile(
                              key,
                              dependencies,
                              () -> {
                                staleStarted.countDown();
                                awaitUnchecked(releaseStale);
                                throw staleFailure;
                              })));
      assertTrue(staleStarted.await(5, TimeUnit.SECONDS));
      Future<Throwable> staleWaiter =
          executor.submit(
              () ->
                  captureFailure(
                      () ->
                          cache.getOrCompile(
                              key,
                              dependencies,
                              () -> {
                                throw new AssertionError("old waiter must not compile");
                              })));
      awaitMissCount(cache, 2);

      cache.clear();
      assertSame(fresh, cache.getOrCompile(key, dependencies, () -> fresh));
      releaseStale.countDown();

      assertSame(staleFailure, staleOwner.get(5, TimeUnit.SECONDS));
      assertSame(staleFailure, staleWaiter.get(5, TimeUnit.SECONDS));
    } finally {
      releaseStale.countDown();
      executor.shutdownNow();
    }

    assertSame(
        fresh,
        cache.getOrCompile(
            key,
            dependencies,
            () -> {
              throw new AssertionError("stale failure must not poison the new generation");
            }));
    assertEquals(new QueryPlanCacheStatistics(1, 3, 0, 0, 1, 4), cache.statistics());
  }

  @Test
  void entityInvalidationOnlyBlocksInflightPlansWithThatDependency() throws Exception {
    ProjectionPlanCache cache = cache(4, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey key = key("entity-race");
    QueryPlanDependencies dependencies = dependencies(PET);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CachedQueryPlan<Pet> compiled = plan("SELECT entity_race");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<CachedQueryPlan<Pet>> compiling =
          executor.submit(
              () ->
                  cache.getOrCompile(
                      key,
                      dependencies,
                      () -> {
                        started.countDown();
                        awaitUnchecked(release);
                        return compiled;
                      }));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertEquals(0, cache.invalidate(OWNER));
      release.countDown();
      assertSame(compiled, compiling.get(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }

    assertSame(
        compiled,
        cache.getOrCompile(
            key,
            dependencies,
            () -> {
              throw new AssertionError("unrelated invalidation must allow publication");
            }));
    assertEquals(new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 4), cache.statistics());
  }

  @Test
  void dependentEntityInvalidationPreventsInflightPublication() throws Exception {
    ProjectionPlanCache cache = cache(4, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey key = key("dependent-entity-race", PET, OWNER);
    QueryPlanDependencies dependencies = dependencies(PET, OWNER);
    CountDownLatch staleStarted = new CountDownLatch(1);
    CountDownLatch releaseStale = new CountDownLatch(1);
    CountDownLatch freshStarted = new CountDownLatch(1);
    CachedQueryPlan<Pet> stale = plan("SELECT stale_after_entity_invalidation");
    CachedQueryPlan<Pet> fresh = plan("SELECT fresh_after_entity_invalidation");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CachedQueryPlan<Pet>> compiling =
          executor.submit(
              () ->
                  cache.getOrCompile(
                      key,
                      dependencies,
                      () -> {
                        staleStarted.countDown();
                        awaitUnchecked(releaseStale);
                        return stale;
                      }));
      assertTrue(staleStarted.await(5, TimeUnit.SECONDS));
      assertEquals(0, cache.invalidate(OWNER));
      Future<CachedQueryPlan<Pet>> afterInvalidation =
          executor.submit(
              () ->
                  cache.getOrCompile(
                      key,
                      dependencies,
                      () -> {
                        freshStarted.countDown();
                        return fresh;
                      }));
      assertTrue(freshStarted.await(5, TimeUnit.SECONDS));
      assertSame(fresh, afterInvalidation.get(5, TimeUnit.SECONDS));
      releaseStale.countDown();
      assertSame(stale, compiling.get(5, TimeUnit.SECONDS));
    } finally {
      releaseStale.countDown();
      executor.shutdownNow();
    }

    assertSame(
        fresh,
        cache.getOrCompile(
            key,
            dependencies,
            () -> {
              throw new AssertionError("stale result must not replace the post-invalidation plan");
            }));
    assertEquals(new QueryPlanCacheStatistics(1, 2, 0, 0, 1, 4), cache.statistics());
  }

  @Test
  void rejectsARequestWhoseDependenciesAreMissingFromTheKey() {
    ProjectionPlanCache cache = cache(4, Duration.ofMinutes(1), new AtomicLong());
    QueryPlanKey key = key("dependency-mismatch");

    cache.getOrCompile(key, dependencies(PET), () -> plan("SELECT dependency_mismatch"));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                cache.getOrCompile(
                    key,
                    dependencies(OWNER),
                    () -> plan("SELECT must_not_replace_dependency_set")));
    assertTrue(failure.getMessage().contains("do not match requested dependencies"));
  }

  @Test
  void cachedPlanReattachesEachInvocationArgumentAndAst() {
    Object firstArgument = new Object();
    Object secondArgument = new Object();
    CachedQueryPlan<Pet> cached = plan("SELECT value_safe");
    SelectStatement firstAst = new SelectStatement(PET_TABLE.selections(), PET_TABLE);
    PetTable secondTable = new PetTable(Identifier.of("second_pet"));
    SelectStatement secondAst = new SelectStatement(secondTable.selections(), secondTable);

    QueryCompilation<Pet> first = cached.bind(firstArgument, firstAst);
    QueryCompilation<Pet> second = cached.bind(secondArgument, secondAst);

    assertSame(cached.plan(), first.plan());
    assertSame(firstAst, first.ast());
    assertSame(secondAst, second.ast());
    assertNotSame(first.ast(), second.ast());
    assertSame(firstArgument, first.argument());
    assertSame(secondArgument, second.argument());
    assertNotSame(first.argument(), second.argument());
  }

  @Test
  void rejectsInvalidConfigurationAndStatistics() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectionPlanCache(-1, Duration.ofMinutes(30), () -> 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectionPlanCache(0, Duration.ZERO, () -> 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new QueryPlanCacheStatistics(0, 0, 0, 0, 1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new QueryPlanCacheStatistics(0, 0, 0, 0, 0, -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new QueryPlanCacheStatistics(1, 0, 0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new QueryPlanCacheStatistics(0, 1, 0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new QueryPlanCacheStatistics(0, 0, 1, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new QueryPlanCacheStatistics(0, 0, 0, 1, 0, 0));
  }

  private static ProjectionPlanCache cache(
      int maximumSize, Duration expiration, AtomicLong ticker) {
    return new ProjectionPlanCache(maximumSize, expiration, ticker::get);
  }

  private static QueryPlanKey key(String name, EntityMeta<?>... entities) {
    EntityMeta<?>[] dependencies = entities.length == 0 ? new EntityMeta<?>[] {PET} : entities;
    return new QueryPlanKey(
        new ResolvedStructureKey.Atom("TEST", List.of(name)),
        QueryPlanKey.Dependencies.from(QueryTestSupport.dependencies(dependencies)),
        RESULT_SHAPE,
        QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
        List.of(),
        DIALECT,
        Map.of());
  }

  private static QueryPlanDependencies dependencies(EntityMeta<?>... entities) {
    return QueryTestSupport.dependencies(entities);
  }

  private static CachedQueryPlan<Pet> plan(String sql) {
    CompiledQueryPlan<Pet, Object> plan =
        new CompiledQueryPlan<>(
            "test",
            new RenderedSql(sql, List.of()),
            (statement, firstIndex, argument, context) -> firstIndex,
            (resultSet, context) -> new Pet(1L));
    return new CachedQueryPlan<>(plan);
  }

  private static Throwable captureFailure(Runnable operation) {
    try {
      operation.run();
      return new AssertionError("compilation unexpectedly succeeded");
    } catch (RuntimeException | Error failure) {
      return failure;
    }
  }

  private static CachedQueryPlan<Pet> blockedPlan(
      String sql, CountDownLatch started, CountDownLatch release) {
    started.countDown();
    awaitUnchecked(release);
    return plan(sql);
  }

  private static void awaitUnchecked(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for cache concurrency test");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("cache concurrency test was interrupted", failure);
    }
  }

  private static void awaitMissCount(ProjectionPlanCache cache, long expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (cache.statistics().missCount() != expected && System.nanoTime() < deadline) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
    if (cache.statistics().missCount() != expected) {
      fail(
          "expected "
              + expected
              + " concurrent cache misses but observed "
              + cache.statistics().missCount());
    }
  }

  private static EntityRuntimeModel<Pet> petRuntimeModel() {
    return new EntityRuntimeModel<>(
        PET,
        ignored -> (resultSet, context) -> new Pet(0L),
        List.of(new PropertyRuntime<>(PET_ID, JdbcCodecs.LONG)));
  }

  private static Dialect testDialect() {
    DialectCapabilities capabilities = TEST_CAPABILITIES;
    return new Dialect() {
      private final IdentifierRules identifiers = identifier -> identifier;
      private final SqlRenderer renderer =
          new StandardSqlRenderer("test", identifiers, capabilities);

      @Override
      public String id() {
        return "test";
      }

      @Override
      public IdentifierRules identifierRules() {
        return identifiers;
      }

      @Override
      public DialectCapabilities capabilities() {
        return capabilities;
      }

      @Override
      public boolean hasStablePlanCacheIdentity() {
        return true;
      }

      @Override
      public SqlRenderer renderer() {
        return renderer;
      }
    };
  }

  private record Pet(Long id) {}

  private record Owner(Long id) {}

  private static final class PetTable extends QueryTable<Pet> {

    private PetTable() {
      super(PET);
    }

    private PetTable(Identifier alias) {
      super(PET, alias);
    }

    @Override
    public PetTable as(Identifier alias) {
      return new PetTable(alias);
    }
  }
}
