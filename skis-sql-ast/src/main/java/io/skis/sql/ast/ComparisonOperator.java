package io.skis.sql.ast;

/** Portable comparison operations represented by the SQL AST. */
public enum ComparisonOperator {
  /** SQL equality ({@code =}). */
  EQUAL(false),
  /** SQL inequality ({@code <>}). */
  NOT_EQUAL(false),
  /** SQL greater-than ({@code >}). */
  GREATER_THAN(true),
  /** SQL greater-than-or-equal ({@code >=}). */
  GREATER_THAN_OR_EQUAL(true),
  /** SQL less-than ({@code <}). */
  LESS_THAN(true),
  /** SQL less-than-or-equal ({@code <=}). */
  LESS_THAN_OR_EQUAL(true);

  private final boolean ordered;

  ComparisonOperator(boolean ordered) {
    this.ordered = ordered;
  }

  /** Whether the operator requires ordered SQL types. */
  public boolean isOrdered() {
    return ordered;
  }
}
