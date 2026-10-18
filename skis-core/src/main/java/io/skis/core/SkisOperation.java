package io.skis.core;

/** Strongly typed framework operation that owns a failure. */
public enum SkisOperation {
  /** A compiled read query, including count and paging probes. */
  QUERY("query"),

  /** A compiled insert, update, or delete operation. */
  MUTATION("mutation"),

  /** A local transaction lifecycle operation. */
  TRANSACTION("transaction");

  private final String label;

  SkisOperation(String label) {
    this.label = label;
  }

  /** Returns the stable lower-case label used in safe diagnostic text. */
  public String label() {
    return label;
  }
}
