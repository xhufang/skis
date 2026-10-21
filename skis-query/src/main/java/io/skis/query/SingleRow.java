package io.skis.query;

import io.skis.query.internal.QueryDsl;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Immutable framework-owned single-row result preserving no-row, SQL NULL, and value states. */
public interface SingleRow<R extends @Nullable Object> {

  /** Returns the shared no-row result. */
  static <R extends @Nullable Object> SingleRow<R> noRow() {
    return QueryDsl.noRow();
  }

  /** Alias for {@link #noRow()}. */
  static <R extends @Nullable Object> SingleRow<R> empty() {
    return noRow();
  }

  /** Returns a result containing one row; its value may be SQL NULL. */
  static <R extends @Nullable Object> SingleRow<R> present(R value) {
    return QueryDsl.presentRow(value);
  }

  /** Returns a result containing one required non-null value. */
  static <R> SingleRow<R> of(R value) {
    return QueryDsl.requiredRow(value);
  }

  /** Maps null to no row and a non-null value to a present row. */
  static <R> SingleRow<R> ofNullable(@Nullable R value) {
    return QueryDsl.nullableRow(value);
  }

  boolean hasRow();

  boolean isNull();

  boolean isPresent();

  boolean isEmpty();

  @NonNull R get();

  @NonNull R orElseThrow();

  <X extends Throwable> @NonNull R orElseThrow(Supplier<? extends X> supplier) throws X;

  @Nullable R orElse(@Nullable R other);

  @Nullable R orElseGet(Supplier<? extends @Nullable R> supplier);

  void ifPresent(Consumer<? super @NonNull R> action);

  void ifPresentOrElse(Consumer<? super @NonNull R> action, Runnable emptyAction);

  Optional<@NonNull R> filter(Predicate<? super @NonNull R> predicate);

  <U> Optional<U> map(Function<? super @NonNull R, ? extends @Nullable U> mapper);

  <U> Optional<U> flatMap(Function<? super @NonNull R, ? extends Optional<? extends U>> mapper);

  Optional<@NonNull R> or(Supplier<? extends Optional<? extends @NonNull R>> supplier);

  /** Returns a value-only view, deliberately collapsing no-row and NULL-row states. */
  Optional<@NonNull R> toOptional();

  /** Returns a zero-or-one element in-memory stream of the non-null value. */
  Stream<@NonNull R> stream();

  /** Compares row presence and value, including present NULL versus no row. */
  @Override
  boolean equals(@Nullable Object other);

  @Override
  int hashCode();

  /** Keeps diagnostics value-redacted without invoking the result value's toString method. */
  @Override
  String toString();
}
