package io.skis.query;

import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;

/** Package-owned adapter from the explicit cursor contract to a closeable JDK stream. */
final class QueryStreams {

  private QueryStreams() {}

  static <R extends @Nullable Object> Stream<R> open(QueryCursor<R> cursor) {
    QueryCursor<R> source = Objects.requireNonNull(cursor, "cursor");
    Spliterator<R> spliterator =
        new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.ORDERED) {
          @Override
          public boolean tryAdvance(Consumer<? super R> action) {
            Objects.requireNonNull(action, "action");
            boolean advanced;
            try {
              advanced = source.advance();
            } catch (RuntimeException | Error failure) {
              closeAfterFailure(source, failure);
              throw failure;
            }
            if (!advanced) {
              source.close();
              return false;
            }
            R value;
            try {
              value = source.current();
            } catch (RuntimeException | Error failure) {
              closeAfterFailure(source, failure);
              throw failure;
            }
            action.accept(value);
            return true;
          }

          @Override
          public @Nullable Spliterator<R> trySplit() {
            return null;
          }
        };
    return StreamSupport.stream(spliterator, false).onClose(source::close);
  }

  private static void closeAfterFailure(QueryCursor<?> cursor, Throwable failure) {
    try {
      cursor.close();
    } catch (RuntimeException | Error closeFailure) {
      if (closeFailure != failure) {
        failure.addSuppressed(closeFailure);
      }
    }
  }
}
