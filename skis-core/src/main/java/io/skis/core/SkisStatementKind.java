package io.skis.core;

/** Strongly typed SQL statement kind reported by execution diagnostics. */
public enum SkisStatementKind {
  /** A SELECT statement, including count and existence probes. */
  SELECT("select"),

  /** An INSERT statement. */
  INSERT("insert"),

  /** An UPDATE statement. */
  UPDATE("update"),

  /** A DELETE statement. */
  DELETE("delete"),

  /** A dialect-specific write statement that is neither insert, update, nor delete. */
  OTHER("other"),

  /** The statement kind is unknown at the reporting point and is left absent. */
  UNKNOWN("unknown");

  private final String label;

  SkisStatementKind(String label) {
    this.label = label;
  }

  /** Returns the stable lower-case label used in safe diagnostic text. */
  public String label() {
    return label;
  }

  /** Maps a generated mutation operation name to its statement kind without exposing free text. */
  public static SkisStatementKind fromOperationName(String operation) {
    return switch (operation) {
      case "insert" -> INSERT;
      case "update", "updateById" -> UPDATE;
      case "delete", "deleteById" -> DELETE;
      default -> OTHER;
    };
  }
}
