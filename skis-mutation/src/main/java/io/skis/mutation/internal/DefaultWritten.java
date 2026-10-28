package io.skis.mutation.internal;

import io.skis.mutation.Written;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Detached immutable write result, containing neither execution state nor JDBC resources. */
final class DefaultWritten<R> implements Written<R> {
  private final int affectedRows;
  private final @Nullable R value;

  DefaultWritten(int affectedRows, @Nullable R value) {
    if (affectedRows < 0) {
      throw new IllegalArgumentException("affected rows must be known and non-negative");
    }
    this.affectedRows = affectedRows;
    this.value = value;
  }

  @Override
  public int affectedRows() {
    return affectedRows;
  }

  @Override
  public boolean hasRow() {
    return value != null;
  }

  @Override
  public R get() {
    R result = value;
    if (result == null) {
      throw new NoSuchElementException("write returned no row");
    }
    return result;
  }

  @Override
  public <X extends Throwable> R orElseThrow(Supplier<? extends X> supplier) throws X {
    R result = value;
    if (result == null) {
      throw supplier.get();
    }
    return result;
  }

  @Override
  public @Nullable R orElse(@Nullable R other) {
    return value == null ? other : value;
  }

  @Override
  public @Nullable R orElseGet(Supplier<? extends @Nullable R> supplier) {
    return value == null ? supplier.get() : value;
  }

  @Override
  public void ifPresent(Consumer<? super R> action) {
    R result = value;
    if (result != null) {
      action.accept(result);
    }
  }

  @Override
  public void ifPresentOrElse(Consumer<? super R> action, Runnable emptyAction) {
    R result = value;
    if (result == null) {
      emptyAction.run();
    } else {
      action.accept(result);
    }
  }

  @Override
  public Optional<R> toOptional() {
    return Optional.ofNullable(value);
  }

  @Override
  public Optional<R> filter(Predicate<? super R> predicate) {
    Objects.requireNonNull(predicate, "predicate");
    R result = value;
    return result == null || !predicate.test(result) ? Optional.empty() : Optional.of(result);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Optional<R> or(Supplier<? extends Optional<? extends R>> supplier) {
    Objects.requireNonNull(supplier, "supplier");
    R result = value;
    return result == null
        ? (Optional<R>) Objects.requireNonNull(supplier.get())
        : Optional.of(result);
  }

  @Override
  public <U> Optional<U> map(Function<? super R, ? extends @Nullable U> mapper) {
    Objects.requireNonNull(mapper, "mapper");
    R result = value;
    return result == null ? Optional.empty() : Optional.ofNullable(mapper.apply(result));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <U> Optional<U> flatMap(Function<? super R, ? extends Optional<? extends U>> mapper) {
    Objects.requireNonNull(mapper, "mapper");
    R result = value;
    return result == null
        ? Optional.empty()
        : (Optional<U>) Objects.requireNonNull(mapper.apply(result));
  }

  @Override
  public Stream<R> stream() {
    R result = value;
    return result == null ? Stream.empty() : Stream.of(result);
  }

  @Override
  public boolean equals(@Nullable Object other) {
    return this == other
        || other instanceof DefaultWritten<?> result
            && affectedRows == result.affectedRows
            && Objects.equals(value, result.value);
  }

  @Override
  public int hashCode() {
    return 31 * affectedRows + Objects.hashCode(value);
  }

  @Override
  public String toString() {
    return "Written[values redacted]";
  }
}
