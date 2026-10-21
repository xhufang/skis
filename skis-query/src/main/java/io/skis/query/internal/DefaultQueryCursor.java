package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.jdbc.internal.JdbcCursor;
import io.skis.query.QueryCursor;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Query-module adapter that enforces result nullability and resource-thread ownership. */
final class DefaultQueryCursor<R extends @Nullable Object> implements QueryCursor<R> {

  private final JdbcCursor<R> delegate;
  private final Thread owner;
  private final boolean nullableResult;

  DefaultQueryCursor(JdbcCursor<R> delegate, boolean nullableResult) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.owner = Thread.currentThread();
    this.nullableResult = nullableResult;
  }

  @Override
  public boolean advance() {
    ExecutionListenerDispatcher.rejectReentrantCall();
    requireOwner("advance");
    return delegate.advance();
  }

  @Override
  public R current() {
    ExecutionListenerDispatcher.rejectReentrantCall();
    requireOwner("current");
    R value = delegate.current();
    if (!nullableResult && value == null) {
      QueryValidationException failure =
          QueryValidationException.of(
              SkisErrorCode.QUERY_NON_NULL_RESULT, "non-null query cursor decoded a null value");
      delegate.fail(failure);
      throw failure;
    }
    return value;
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override
  public void close() {
    ExecutionListenerDispatcher.rejectReentrantCall();
    requireOwner("close");
    delegate.close();
  }

  private void requireOwner(String operation) {
    if (Thread.currentThread() != owner) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_CURSOR_THREAD,
          "query cursor " + operation + " must run on its resource-owning thread");
    }
  }
}
