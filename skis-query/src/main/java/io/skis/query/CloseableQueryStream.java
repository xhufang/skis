package io.skis.query;

import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;

/** Explicit resource wrapper around a lazy stream backed by a {@link QueryCursor}. */
public final class CloseableQueryStream<R extends @Nullable Object> implements AutoCloseable {

  private final QueryCursor<R> cursor;
  private final Stream<R> stream;
  private final AtomicBoolean closed = new AtomicBoolean();

  CloseableQueryStream(QueryCursor<R> cursor) {
    this.cursor = Objects.requireNonNull(cursor, "cursor");
    Spliterator<R> spliterator =
        new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.ORDERED) {
          @Override
          public boolean tryAdvance(Consumer<? super R> action) {
            Objects.requireNonNull(action, "action");
            if (!cursor.advance()) {
              return false;
            }
            action.accept(cursor.current());
            return true;
          }

          @Override
          public @Nullable Spliterator<R> trySplit() {
            return null;
          }
        };
    this.stream = StreamSupport.stream(spliterator, false).onClose(this::close);
  }

  /** Returns the single lazy stream owned by this wrapper. */
  public Stream<R> stream() {
    return stream;
  }

  public boolean isClosed() {
    return closed.get() || cursor.isClosed();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      cursor.close();
    }
  }
}
