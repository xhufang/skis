package io.skis.runtime;

import io.skis.core.ExecutionOptions;
import io.skis.core.SkisErrorCode;
import io.skis.core.TransactionException;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.jdbc.internal.JdbcTransaction;
import io.skis.mutation.DeleteWrite;
import io.skis.mutation.InsertInto;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.UpdateSet;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryOperations;
import io.skis.query.QueryTable;
import io.skis.query.SelectDescription;
import io.skis.query.SelectFromStep;
import io.skis.query.SelectQuery;
import io.skis.query.Selectable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Default explicit transaction session. */
final class DefaultSkisSession implements SkisSession {

  private final JdbcTransaction transaction;
  private final QueryOperations queries;
  private final MutationOperations mutations;
  private final List<Runnable> afterCommitCallbacks = new ArrayList<>();

  DefaultSkisSession(
      JdbcTransaction transaction, QueryOperations queries, MutationOperations mutations) {
    this.transaction = Objects.requireNonNull(transaction, "transaction");
    this.queries = Objects.requireNonNull(queries, "queries");
    this.mutations = Objects.requireNonNull(mutations, "mutations");
  }

  @Override
  public <E> Optional<E> findById(QueryTable<E> table, Object id) {
    requireActive();
    return queries.findById(table, id);
  }

  @Override
  public <E> Optional<E> findById(
      QueryTable<E> table, Object id, ExecutionOptions executionOptions) {
    requireActive();
    return queries.findById(table, id, executionOptions);
  }

  @Override
  public <E> SelectQuery<E> selectFrom(QueryTable<E> table) {
    requireActive();
    return queries.selectFrom(table);
  }

  @Override
  public <E> SelectFromStep<SelectQuery<E>> select(QueryTable<E> table) {
    requireActive();
    return queries.select(table);
  }

  @Override
  public <E> SelectFromStep<SelectQuery<@Nullable E>> selectNullable(QueryTable<E> table) {
    requireActive();
    return queries.selectNullable(table);
  }

  @Override
  public <V> SelectFromStep<SelectQuery<V>> select(NonNullSelectable<V> column) {
    requireActive();
    return queries.select(column);
  }

  @Override
  public <V> SelectFromStep<SelectQuery<@Nullable V>> select(Selectable<V> column) {
    requireActive();
    return queries.select(column);
  }

  @Override
  public <V> SelectFromStep<SelectQuery<@Nullable V>> selectNullable(NonNullSelectable<V> column) {
    requireActive();
    return queries.selectNullable(column);
  }

  @Override
  public <R> SelectFromStep<SelectQuery<R>> select(ProjectionSelection<R> projection) {
    requireActive();
    return queries.select(projection);
  }

  @Override
  public <R> SelectFromStep<SelectQuery<R>> select(ProjectionType<R> projection) {
    requireActive();
    return queries.select(projection);
  }

  @Override
  public <R extends @Nullable Object> SelectQuery<R> query(SelectDescription<R> description) {
    requireActive();
    return queries.query(description);
  }

  @Override
  public <E> InsertInto<E> insertInto(QueryTable<E> table) {
    requireActive();
    return mutations.insertInto(table);
  }

  @Override
  public <E> UpdateSet<E> update(QueryTable<E> table) {
    requireActive();
    return mutations.update(table);
  }

  @Override
  public <E> DeleteWrite<E> deleteFrom(QueryTable<E> table) {
    requireActive();
    return mutations.deleteFrom(table);
  }

  @Override
  public <E> int updateById(QueryTable<E> table, E value) {
    requireActive();
    return mutations.updateById(table, value);
  }

  @Override
  public <E> int updateById(QueryTable<E> table, E value, ExecutionOptions executionOptions) {
    requireActive();
    return mutations.updateById(table, value, executionOptions);
  }

  @Override
  public <E> int deleteById(QueryTable<E> table, Object id) {
    requireActive();
    return mutations.deleteById(table, id);
  }

  @Override
  public <E> int deleteById(QueryTable<E> table, Object id, ExecutionOptions executionOptions) {
    requireActive();
    return mutations.deleteById(table, id, executionOptions);
  }

  @Override
  public void afterCommit(Runnable callback) {
    requireActive();
    afterCommitCallbacks.add(Objects.requireNonNull(callback, "callback"));
  }

  @Override
  public void commit() {
    ExecutionListenerDispatcher.rejectReentrantCall();
    requireActive();
    try {
      transaction.commit();
    } catch (RuntimeException | Error failure) {
      afterCommitCallbacks.clear();
      throw failure;
    }
    List<Runnable> committedCallbacks = List.copyOf(afterCommitCallbacks);
    afterCommitCallbacks.clear();
    Throwable callbackFailure = null;
    for (Runnable callback : committedCallbacks) {
      try {
        callback.run();
      } catch (RuntimeException | Error failure) {
        if (callbackFailure == null) {
          callbackFailure = failure;
        } else {
          callbackFailure.addSuppressed(failure);
        }
      }
    }
    if (callbackFailure instanceof Error error) {
      throw error;
    }
    if (callbackFailure != null) {
      throw TransactionException.of(
          SkisErrorCode.TRANSACTION_AFTER_COMMIT_FAILED,
          "transaction committed but an afterCommit callback failed",
          callbackFailure);
    }
  }

  @Override
  public void rollback() {
    ExecutionListenerDispatcher.rejectReentrantCall();
    requireActive();
    try {
      transaction.rollback();
    } finally {
      afterCommitCallbacks.clear();
    }
  }

  @Override
  public boolean active() {
    return transaction.active();
  }

  @Override
  public void close() {
    ExecutionListenerDispatcher.rejectReentrantCall();
    afterCommitCallbacks.clear();
    transaction.close();
  }

  private void requireActive() {
    if (!active()) {
      throw TransactionException.of(
          SkisErrorCode.SESSION_INACTIVE, "SKIS session is no longer active");
    }
  }
}
