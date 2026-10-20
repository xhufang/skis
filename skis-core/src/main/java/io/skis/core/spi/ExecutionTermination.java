package io.skis.core.spi;

/** How an observed operation or SQL attempt reached its real terminal state. */
public enum ExecutionTermination {
  COMPLETED("completed"),
  EXHAUSTED("exhausted"),
  CLOSED_EARLY("closed-early"),
  FAILED("failed");

  private final String label;

  ExecutionTermination(String label) {
    this.label = label;
  }

  /** Returns the stable lower-case label. */
  public String label() {
    return label;
  }
}
