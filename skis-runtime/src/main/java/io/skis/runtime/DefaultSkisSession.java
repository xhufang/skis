package io.skis.runtime;

import io.skis.core.ExecutionOptions;
import io.skis.core.SkisErrorCode;
import io.skis.core.TransactionException;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.jdbc.internal.JdbcTransaction;
import io.skis.mutation.DeleteWrite;
import io.skis.mutation.EntityById;
import io.skis.mutation.InsertInto;
import io.skis.mutation.MergeInto;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.UpdateSet;
import io.skis.query.ExecutableSelectQuery;
import io.skis.query.ExecutableSingleColumnSelect;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryOperations;
import io.skis.query.QueryRequest;
import io.skis.query.QueryTable;
import io.skis.query.Row2;
import io.skis.query.Row3;
import io.skis.query.SelectFromStep;
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
  public <E> ExecutableSelectQuery<E> selectFrom(QueryTable<E> table) {
    requireActive();
    return queries.selectFrom(table);
  }

  @Override
  public <E> SelectFromStep<ExecutableSelectQuery<E>> select(QueryTable<E> table) {
    requireActive();
    return queries.select(table);
  }

  @Override
  public <E> SelectFromStep<ExecutableSelectQuery<@Nullable E>> selectNullable(
      QueryTable<E> table) {
    requireActive();
    return queries.selectNullable(table);
  }

  @Override
  public <V> SelectFromStep<ExecutableSingleColumnSelect<V>> select(NonNullSelectable<V> column) {
    requireActive();
    return queries.select(column);
  }

  @Override
  public <V> SelectFromStep<ExecutableSingleColumnSelect<@Nullable V>> select(
      Selectable<V> column) {
    requireActive();
    return queries.select(column);
  }

  @Override
  public <V> SelectFromStep<ExecutableSingleColumnSelect<@Nullable V>> selectNullable(
      NonNullSelectable<V> column) {
    requireActive();
    return queries.selectNullable(column);
  }

  @Override
  public <R> SelectFromStep<ExecutableSelectQuery<R>> select(ProjectionSelection<R> projection) {
    requireActive();
    return queries.select(projection);
  }

  @Override
  public <R> SelectFromStep<ExecutableSelectQuery<R>> select(ProjectionType<R> projection) {
    requireActive();
    return queries.select(projection);
  }

  @Override
  public <T extends @Nullable Object> T run(QueryRequest<T> request) {
    requireActive();
    return queries.run(request);
  }

  @Override
  public <E> InsertInto<E> insertInto(QueryTable<E> table) {
    requireActive();
    return mutations.insertInto(table);
  }

  @Override
  public <E> MergeInto<E> mergeInto(QueryTable<E> table) {
    requireActive();
    return mutations.mergeInto(table);
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
  public <E> EntityById<E> updateById(QueryTable<E> table) {
    requireActive();
    return mutations.updateById(table);
  }

  @Override
  public <E> EntityById<E> deleteById(QueryTable<E> table) {
    requireActive();
    return mutations.deleteById(table);
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

  @Override
  public <A, B> SelectFromStep<ExecutableSelectQuery<Row2<@Nullable A, @Nullable B>>> select(
      Selectable<A> first, Selectable<B> second) {
    requireActive();
    return queries.select(first, second);
  }

  @Override
  public <A, B> SelectFromStep<ExecutableSelectQuery<Row2<A, @Nullable B>>> select(
      NonNullSelectable<A> first, Selectable<B> second) {
    requireActive();
    return queries.select(first, second);
  }

  @Override
  public <A, B> SelectFromStep<ExecutableSelectQuery<Row2<@Nullable A, B>>> select(
      Selectable<A> first, NonNullSelectable<B> second) {
    requireActive();
    return queries.select(first, second);
  }

  @Override
  public <A, B> SelectFromStep<ExecutableSelectQuery<Row2<A, B>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second) {
    requireActive();
    return queries.select(first, second);
  }

  @Override
  public <A, B, C>
      SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, @Nullable B, @Nullable C>>> select(
          Selectable<A> first, Selectable<B> second, Selectable<C> third) {
    requireActive();
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, @Nullable B, @Nullable C>>> select(
      NonNullSelectable<A> first, Selectable<B> second, Selectable<C> third) {
    requireActive();
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, B, @Nullable C>>> select(
      Selectable<A> first, NonNullSelectable<B> second, Selectable<C> third) {
    requireActive();
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, B, @Nullable C>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second, Selectable<C> third) {
    requireActive();
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, @Nullable B, C>>> select(
      Selectable<A> first, Selectable<B> second, NonNullSelectable<C> third) {
    requireActive();
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, @Nullable B, C>>> select(
      NonNullSelectable<A> first, Selectable<B> second, NonNullSelectable<C> third) {
    requireActive();
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, B, C>>> select(
      Selectable<A> first, NonNullSelectable<B> second, NonNullSelectable<C> third) {
    requireActive();
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, B, C>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second, NonNullSelectable<C> third) {
    requireActive();
    return queries.select(first, second, third);
  }
}
