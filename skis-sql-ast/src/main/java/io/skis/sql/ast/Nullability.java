package io.skis.sql.ast;

import org.jspecify.annotations.Nullable;

/** Explicit SQL nullability carried by every expression node. */
public enum Nullability {
  /** The expression cannot evaluate to SQL {@code NULL}. */
  NON_NULL,
  /** The expression may evaluate to SQL {@code NULL}. */
  NULLABLE;

  /** Converts boolean metadata into the explicit model. */
  public static Nullability of(boolean nullable) {
    return nullable ? NULLABLE : NON_NULL;
  }

  /** Returns whether SQL evaluation may produce {@code NULL}. */
  public boolean isNullable() {
    return this == NULLABLE;
  }

  /** Propagates nullability from two operands. */
  public Nullability union(@Nullable Nullability other) {
    if (other == null) {
      throw new NullPointerException("other");
    }
    return this == NULLABLE || other == NULLABLE ? NULLABLE : NON_NULL;
  }
}
