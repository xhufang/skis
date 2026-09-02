package io.skis.sql.ast;

/** Portable null placement of one SELECT ordering item. */
public enum NullOrder {
  DIALECT_DEFAULT,
  FIRST,
  LAST
}
