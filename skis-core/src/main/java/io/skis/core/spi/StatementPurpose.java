package io.skis.core.spi;

/** Purpose of one SQL attempt within its owning operation. */
public enum StatementPurpose {
  PRIMARY("primary"),
  PAGE_CONTENT("page-content"),
  PAGE_COUNT("page-count");

  private final String label;

  StatementPurpose(String label) {
    this.label = label;
  }

  /** Returns the stable lower-case label. */
  public String label() {
    return label;
  }
}
