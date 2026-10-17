package io.skis.query;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Immutable single-row result that preserves no-row, SQL NULL, and non-null value states. */
public final class SingleRow<R extends @Nullable Object> {

  private static final SingleRow<?> NO_ROW = new SingleRow<>(false, null);

  private final boolean hasRow;
  private final @Nullable R value;

  private SingleRow(boolean hasRow, @Nullable R value) {
    this.hasRow = hasRow;
    this.value = value;
  }

  /** Returns the shared no-row result. */
  @SuppressWarnings("unchecked")
  public static <R extends @Nullable Object> SingleRow<R> noRow() {
    return (SingleRow<R>) NO_ROW;
  }

  /** Alias for {@link #noRow()}. */
  public static <R extends @Nullable Object> SingleRow<R> empty() {
    return noRow();
  }

  /** Returns a result containing one row; its value may be SQL NULL. */
  public static <R extends @Nullable Object> SingleRow<R> present(R value) {
    return new SingleRow<>(true, value);
  }

  /** Returns a result containing one required non-null value. */
  public static <R> SingleRow<R> of(R value) {
    return new SingleRow<>(true, Objects.requireNonNull(value, "value"));
  }

  /** Maps null to no row and a non-null value to a present row. */
  public static <R> SingleRow<R> ofNullable(@Nullable R value) {
    return value == null ? noRow() : of(value);
  }

  public boolean hasRow() {
    return hasRow;
  }

  public boolean isNull() {
    return hasRow && value == null;
  }

  public boolean isPresent() {
    return value != null;
  }

  public boolean isEmpty() {
    return value == null;
  }

  public @NonNull R get() {
    if (value == null) {
      throw new NoSuchElementException(hasRow ? "row value is null" : "no row is present");
    }
    return value;
  }

  public @NonNull R orElseThrow() {
    return get();
  }

  public <X extends Throwable> @NonNull R orElseThrow(Supplier<? extends X> supplier) throws X {
    if (value == null) {
      throw supplier.get();
    }
    return value;
  }

  public @Nullable R orElse(@Nullable R other) {
    return value != null ? value : other;
  }

  public @Nullable R orElseGet(Supplier<? extends @Nullable R> supplier) {
    return value != null ? value : supplier.get();
  }

  public void ifPresent(Consumer<? super @NonNull R> action) {
    if (value != null) {
      action.accept(value);
    }
  }

  public void ifPresentOrElse(Consumer<? super @NonNull R> action, Runnable emptyAction) {
    if (value != null) {
      action.accept(value);
    } else {
      emptyAction.run();
    }
  }

  public Optional<@NonNull R> filter(Predicate<? super @NonNull R> predicate) {
    Objects.requireNonNull(predicate, "predicate");
    if (value == null || !predicate.test(value)) {
      return Optional.empty();
    }
    return Optional.of(value);
  }

  public <U> Optional<U> map(Function<? super @NonNull R, ? extends @Nullable U> mapper) {
    Objects.requireNonNull(mapper, "mapper");
    return value == null ? Optional.empty() : Optional.ofNullable(mapper.apply(value));
  }

  @SuppressWarnings("unchecked")
  public <U> Optional<U> flatMap(
      Function<? super @NonNull R, ? extends Optional<? extends U>> mapper) {
    Objects.requireNonNull(mapper, "mapper");
    if (value == null) {
      return Optional.empty();
    }
    return (Optional<U>) Objects.requireNonNull(mapper.apply(value));
  }

  @SuppressWarnings("unchecked")
  public Optional<@NonNull R> or(Supplier<? extends Optional<? extends @NonNull R>> supplier) {
    Objects.requireNonNull(supplier, "supplier");
    if (value != null) {
      return Optional.of(value);
    }
    return (Optional<@NonNull R>) Objects.requireNonNull(supplier.get());
  }

  /** Returns a value-only view, deliberately collapsing no-row and NULL-row states. */
  public Optional<@NonNull R> toOptional() {
    return Optional.ofNullable(value);
  }

  /** Returns a zero-or-one element in-memory stream of the non-null value. */
  public Stream<@NonNull R> stream() {
    return value == null ? Stream.empty() : Stream.of(value);
  }

  @Override
  public boolean equals(@Nullable Object other) {
    return this == other
        || other instanceof SingleRow<?> row
            && hasRow == row.hasRow
            && Objects.equals(value, row.value);
  }

  @Override
  public int hashCode() {
    return 31 * Boolean.hashCode(hasRow) + Objects.hashCode(value);
  }

  /** Keeps diagnostics value-redacted and does not invoke the result value's toString method. */
  @Override
  public String toString() {
    return hasRow
        ? (value == null ? "SingleRow[present-null]" : "SingleRow[present]")
        : "SingleRow[no-row]";
  }
}
