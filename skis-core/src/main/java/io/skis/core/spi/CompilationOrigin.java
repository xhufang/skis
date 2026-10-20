package io.skis.core.spi;

/** Source of one actual plan compilation observed by an executor. */
public enum CompilationOrigin {
  /** Compilation performed for a terminal invocation. */
  ON_DEMAND("on-demand"),

  /** Compilation performed while the executor is being assembled. */
  PREWARM("prewarm");

  private final String label;

  CompilationOrigin(String label) {
    this.label = label;
  }

  /** Returns the stable lower-case label. */
  public String label() {
    return label;
  }
}
