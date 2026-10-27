package io.skis.mutation;

import io.skis.core.MutationException;
import io.skis.core.SkisConfigurationException;
import io.skis.core.SkisErrorCode;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe 41/10/12-bit generator with a fixed 2026-01-01 UTC epoch.
 *
 * <p>Deployments must allocate unique nodes and reuse one instance per node in a process. Before
 * reusing a node after restart, physical time must exceed its last issued timestamp. No persistent
 * high-water mark or distributed node lease is provided. Clock rollback and sequence exhaustion
 * fail immediately; failed writes do not reclaim IDs.
 */
public final class SnowflakeIdGenerator {
  private static final long EPOCH_MILLIS = 1767225600000L;
  private final int nodeId;
  private final AtomicLong state = new AtomicLong(-1L);

  private SnowflakeIdGenerator(int nodeId) {
    this.nodeId = nodeId;
  }

  /** Creates a generator for an explicitly allocated node in [0, 1023]. */
  public static SnowflakeIdGenerator ofNode(int nodeId) {
    if (nodeId < 0 || nodeId > 1023) {
      throw SkisConfigurationException.of(
          SkisErrorCode.ASSEMBLY_CONFIGURATION_INVALID, "snowflake node must be in [0, 1023]");
    }
    return new SnowflakeIdGenerator(nodeId);
  }

  /** Atomically allocates one positive ID without waiting for the wall clock. */
  public long nextId() {
    for (; ; ) {
      long previous = state.get();
      long next = nextState(previous, System.currentTimeMillis() - EPOCH_MILLIS, nodeId);
      if (state.compareAndSet(previous, next)) {
        return ((next >>> 12) << 22) | ((long) nodeId << 12) | (next & 4095L);
      }
    }
  }

  // Pure production transition, also permitting deterministic boundary tests in this package.
  static long nextState(long previous, long millis, int nodeId) {
    if (millis < 0 || millis >= (1L << 41)) {
      throw failed("snowflake time is outside its epoch range");
    }
    if (previous >= 0 && millis < (previous >>> 12)) {
      throw failed("snowflake wall clock moved backwards");
    }
    long sequence = previous >= 0 && millis == (previous >>> 12) ? (previous & 4095L) + 1 : 0;
    if (millis == 0 && nodeId == 0 && sequence == 0) {
      sequence = 1;
    }
    if (sequence > 4095L) {
      throw failed("snowflake sequence exhausted for the current millisecond");
    }
    return (millis << 12) | sequence;
  }

  private static MutationException failed(String detail) {
    return MutationException.of(SkisErrorCode.MUTATION_ID_GENERATION_FAILED, detail);
  }
}
