package io.skis.mutation;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** An already completed write and its optional non-null result; access never executes SQL. */
public interface Written<R> {

  int affectedRows();

  boolean hasRow();

  default boolean isPresent() {
    return hasRow();
  }

  default boolean isEmpty() {
    return !hasRow();
  }

  R get();

  default R orElseThrow() {
    return get();
  }

  <X extends Throwable> R orElseThrow(Supplier<? extends X> supplier) throws X;

  @Nullable R orElse(@Nullable R other);

  @Nullable R orElseGet(Supplier<? extends @Nullable R> supplier);

  void ifPresent(Consumer<? super R> action);

  void ifPresentOrElse(Consumer<? super R> action, Runnable emptyAction);

  Optional<R> toOptional();

  Optional<R> filter(Predicate<? super R> predicate);

  Optional<R> or(Supplier<? extends Optional<? extends R>> supplier);

  <U> Optional<U> map(Function<? super R, ? extends @Nullable U> mapper);

  <U> Optional<U> flatMap(Function<? super R, ? extends Optional<? extends U>> mapper);

  Stream<R> stream();
}
