package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProjectionPlanCacheTest {

  @Test
  void zeroCapacityRepresentsADisabledSharedCache() {
    ProjectionPlanCache cache =
        new ProjectionPlanCache(0, Duration.ofMinutes(30), () -> 0L);

    assertEquals(new QueryPlanCacheStatistics(0, 0, 0, 0, 0, 0), cache.statistics());
    cache.clear();
    assertEquals(new QueryPlanCacheStatistics(0, 0, 0, 0, 0, 0), cache.statistics());
  }

  @Test
  void disabledCacheStillRejectsInvalidConfiguration() {
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
}
