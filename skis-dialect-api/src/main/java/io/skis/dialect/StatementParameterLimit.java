package io.skis.dialect;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Connection-independent conservative bind-parameter limit for one SQL statement.
 *
 * <p>{@link Kind#UNKNOWN} is deliberately distinct from {@link Kind#UNBOUNDED}: callers must not
 * treat missing limit information as permission to create an arbitrarily large statement. A
 * connection-aware layer may retain or further restrict a dialect-level declaration, but must not
 * raise an explicit limit.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class StatementParameterLimit {

  /** The kind of limit declared by a dialect. */
  public enum Kind {
    /** The dialect cannot provide a safe static limit. */
    UNKNOWN,

    /** The dialect declares no statement parameter count limit. */
    UNBOUNDED,

    /** The dialect declares a finite positive maximum. */
    EXPLICIT
  }

  private static final StatementParameterLimit UNKNOWN =
      new StatementParameterLimit(Kind.UNKNOWN, OptionalInt.empty());
  private static final StatementParameterLimit UNBOUNDED =
      new StatementParameterLimit(Kind.UNBOUNDED, OptionalInt.empty());

  private final Kind kind;
  private final OptionalInt maximum;

  private StatementParameterLimit(Kind kind, OptionalInt maximum) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.maximum = Objects.requireNonNull(maximum, "maximum");
  }

  /** Returns the shared value for a limit that is not known statically. */
  public static StatementParameterLimit unknown() {
    return UNKNOWN;
  }

  /** Returns the shared value for a dialect with no statement parameter count limit. */
  public static StatementParameterLimit unbounded() {
    return UNBOUNDED;
  }

  /**
   * Returns a finite positive statement parameter limit.
   *
   * <p>{@link Integer#MAX_VALUE} is valid when it is the confirmed finite limit. It must not be used
   * as a sentinel for {@link #unknown()} or {@link #unbounded()}.
   */
  public static StatementParameterLimit explicit(int maximum) {
    if (maximum <= 0) {
      throw new IllegalArgumentException("maximum must be greater than zero");
    }
    return new StatementParameterLimit(Kind.EXPLICIT, OptionalInt.of(maximum));
  }

  /** Returns whether this value is unknown, unbounded, or explicit. */
  public Kind kind() {
    return kind;
  }

  /** Returns the finite maximum when {@link #kind()} is {@link Kind#EXPLICIT}. */
  public OptionalInt maximum() {
    return maximum;
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof StatementParameterLimit limit
            && kind == limit.kind
            && maximum.equals(limit.maximum);
  }

  @Override
  public int hashCode() {
    return 31 * kind.hashCode() + maximum.hashCode();
  }

  @Override
  public String toString() {
    return switch (kind) {
      case UNKNOWN -> "StatementParameterLimit[unknown]";
      case UNBOUNDED -> "StatementParameterLimit[unbounded]";
      case EXPLICIT -> "StatementParameterLimit[maximum=" + maximum.orElseThrow() + ']';
    };
  }
}
