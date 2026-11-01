package io.skis.mutation;

import java.util.Objects;

/**
 * Immutable batch budgets; the dialect's explicit parameter limit also applies.
 *
 * <p>The memory budget bounds the estimated framework chunk buffers, excluding caller-owned
 * entities, variable JDBC payloads and driver-private buffers. It is not a JVM heap limit.
 */
public record BatchOptions(
    BatchMode mode, int chunkSize, int parameterBudget, long memoryBudgetBytes) {

  public static final int MAX_CHUNK_SIZE = 256;
  public static final BatchOptions DEFAULT =
      new BatchOptions(BatchMode.ADD_BATCH, MAX_CHUNK_SIZE, 1024, 8L * 1024 * 1024);

  public BatchOptions {
    Objects.requireNonNull(mode, "mode");
    if (chunkSize < 1 || chunkSize > MAX_CHUNK_SIZE || parameterBudget < 1 || memoryBudgetBytes < 1)
      throw new IllegalArgumentException("invalid batch row, parameter or memory budget");
  }

  public BatchOptions withMode(BatchMode mode) {
    return new BatchOptions(mode, chunkSize, parameterBudget, memoryBudgetBytes);
  }
}
