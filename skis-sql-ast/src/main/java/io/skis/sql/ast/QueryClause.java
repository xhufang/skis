package io.skis.sql.ast;

/** SQL clause or relation position that owns an expression or nested query block. */
public enum QueryClause {
  SELECT("SELECT"),
  JOIN_SOURCE("JOIN source"),
  JOIN_ON("JOIN ON"),
  WHERE("WHERE"),
  GROUP_BY("GROUP BY"),
  HAVING("HAVING"),
  ORDER_BY("ORDER BY"),
  PAGINATION("pagination");

  private final String displayName;

  QueryClause(String displayName) {
    this.displayName = displayName;
  }

  /** Human-readable clause name used by validation diagnostics. */
  public String displayName() {
    return displayName;
  }
}
