package io.skis.query;

import io.skis.jdbc.JdbcCursor;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Query-module adapter that keeps JDBC infrastructure types out of the user API. */
final class DefaultQueryCursor<R> implements QueryCursor<R> {

  private final JdbcCursor<R> delegate;

  DefaultQueryCursor(JdbcCursor<R> delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public boolean advance() {
    return delegate.advance();
  }

  @Override
  public R current() {
    return Objects.requireNonNull(delegate.current(), "non-null query cursor decoded a null value");
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override
  public void close() {
    delegate.close();
  }

  static <R> QueryCursor<@Nullable R> nullable(JdbcCursor<R> delegate) {
    return new NullableCursor<>(delegate);
  }

  private record NullableCursor<R>(JdbcCursor<R> delegate) implements QueryCursor<@Nullable R> {

    private NullableCursor(JdbcCursor<R> delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public boolean advance() {
      return delegate.advance();
    }

    @Override
    public @Nullable R current() {
      return delegate.current();
    }

    @Override
    public boolean isClosed() {
      return delegate.isClosed();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
