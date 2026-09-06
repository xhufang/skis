package io.skis.query;

import org.jspecify.annotations.Nullable;

/** Nullable result that distinguishes no row from a row containing SQL NULL or no joined entity. */
public sealed interface SingleRow<T> permits SingleRow.NoRow, SingleRow.Present {

  /** No result row existed. */
  record NoRow<T>() implements SingleRow<T> {}

  /** A result row existed; its selected scalar or outer-joined entity may be null. */
  record Present<T>(@Nullable T value) implements SingleRow<T> {}

  static <T> SingleRow<T> noRow() {
    return new NoRow<>();
  }

  static <T> SingleRow<T> present(@Nullable T value) {
    return new Present<T>(value);
  }
}
