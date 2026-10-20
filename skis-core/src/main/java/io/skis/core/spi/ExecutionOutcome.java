package io.skis.core.spi;

/** Final result of one observed compilation or execution lifecycle. */
public enum ExecutionOutcome {
  /** The observed lifecycle completed successfully. */
  SUCCEEDED("succeeded"),

  /** The observed lifecycle failed. */
  FAILED("failed");

  private final String label;

  ExecutionOutcome(String label) {
    this.label = label;
  }

  /** Returns the stable lower-case label. */
  public String label() {
    return label;
  }
}
