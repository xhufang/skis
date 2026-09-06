package io.skis.sql.ast;

/** Portable join kinds represented directly by the SELECT AST. */
public enum JoinType {
  INNER,
  LEFT,
  RIGHT,
  FULL,
  CROSS
}
