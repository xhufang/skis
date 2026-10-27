package io.skis.mutation;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.MutationException;
import io.skis.core.SkisConfigurationException;
import io.skis.core.SkisErrorCode;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {
  @Test
  void validatesNodesWithoutInventingAnAllocation() {
    assertEquals(SkisErrorCode.ASSEMBLY_CONFIGURATION_INVALID,
        assertThrows(SkisConfigurationException.class,
            () -> SnowflakeIdGenerator.ofNode(-1)).code());
    assertThrows(SkisConfigurationException.class, () -> SnowflakeIdGenerator.ofNode(1024));
    assertEquals(0, (SnowflakeIdGenerator.ofNode(0).nextId() >>> 12) & 1023);
    assertEquals(1023, (SnowflakeIdGenerator.ofNode(1023).nextId() >>> 12) & 1023);
  }

  @Test
  void productionTransitionEnforcesTimeSequenceAndPositiveIdBoundaries() {
    assertEquals(1, SnowflakeIdGenerator.nextState(-1, 0, 0));
    assertEquals(0, SnowflakeIdGenerator.nextState(-1, 0, 1));
    long initial = SnowflakeIdGenerator.nextState(-1, 100, 17);
    assertEquals(100L << 12, initial);
    assertEquals(initial + 1, SnowflakeIdGenerator.nextState(initial, 100, 17));
    assertEquals(101L << 12, SnowflakeIdGenerator.nextState(initial + 1, 101, 17));
    for (long time : new long[] {-1, 99, 1L << 41}) {
      assertEquals(SkisErrorCode.MUTATION_ID_GENERATION_FAILED,
          assertThrows(MutationException.class,
              () -> SnowflakeIdGenerator.nextState(initial, time, 17)).code());
    }
    assertThrows(MutationException.class,
        () -> SnowflakeIdGenerator.nextState((100L << 12) | 4095, 100, 17));
    assertEquals(101L << 12,
        SnowflakeIdGenerator.nextState((100L << 12) | 4095, 101, 17));
    assertEquals(((1L << 41) - 1) << 12,
        SnowflakeIdGenerator.nextState(-1, (1L << 41) - 1, 1023));
  }

  @Test
  void oneSharedGeneratorAllocatesUniqueIdsAcrossConcurrentCallers() throws Exception {
    var generator = SnowflakeIdGenerator.ofNode(37);
    // Fewer than 4096 total allocations: this checks contention without depending on clock waits.
    var tasks = IntStream.range(0, 8).<Callable<long[]>>mapToObj(worker -> () -> {
      long[] ids = new long[128];
      for (int index = 0; index < ids.length; index++) {
        ids[index] = generator.nextId();
        assertTrue(ids[index] > 0);
        assertEquals(37, (ids[index] >>> 12) & 1023);
        if (index > 0) {
          assertTrue(ids[index] > ids[index - 1]);
        }
      }
      return ids;
    }).toList();
    var unique = new HashSet<Long>();
    try (var pool = Executors.newFixedThreadPool(8)) {
      for (var result : pool.invokeAll(tasks)) {
        for (long id : result.get()) {
          assertTrue(unique.add(id));
        }
      }
    }
    assertEquals(1024, unique.size());
  }
}
