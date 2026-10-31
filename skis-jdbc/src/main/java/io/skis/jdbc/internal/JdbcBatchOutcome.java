package io.skis.jdbc.internal;

import org.jspecify.annotations.Nullable;

/** One detached driver report; missing submitted positions retain unknown execution status. */
public record JdbcBatchOutcome(
    int[] counts,
    int submitted,
    boolean executed,
    int failedInput,
    @Nullable RuntimeException failure) {

  @Override
  public String toString() {
    return "JdbcBatchOutcome[submitted="
        + submitted
        + ", executed="
        + executed
        + ", values redacted]";
  }
}
