package io.skis.runtime;

import io.skis.core.ExecutionOptions;
import io.skis.core.TransactionException;
import io.skis.jdbc.JdbcTransaction;
import io.skis.metadata.EntityMeta;
import io.skis.mutation.MutationOperations;
import io.skis.query.NonNullQueryColumn;
import io.skis.query.NullableQueryColumn;
import io.skis.query.NullableSelectFromStep;
import io.skis.query.QueryOperations;
import io.skis.query.QueryTable;
import io.skis.query.SelectQuery;
import io.skis.query.SelectFromStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
  public <E> Optional<E> findById(EntityMeta<E> entity, Object id) {
    requireActive();
    return queries.findById(entity, id);
  }

  @Override
  public <E> Optional<E> findById(
      EntityMeta<E> entity, Object id, ExecutionOptions executionOptions) {
    requireActive();
    return queries.findById(entity, id, executionOptions);
  }

  @Override
  public <E> SelectQuery<E, E> selectFrom(QueryTable<E> table) {
    requireActive();
    return queries.selectFrom(table);
  }

  @Override
  public <E, V> SelectFromStep<E, V> select(NonNullQueryColumn<E, V> column) {
    requireActive();
    return queries.select(column);
  }

  @Override
  public <E, V> NullableSelectFromStep<E, V> select(NullableQueryColumn<E, V> column) {
    requireActive();
    return queries.select(column);
  }

  @Override
  public <E, R> SelectQuery<E, R> selectProjection(QueryTable<E> table, Class<R> projectionType) {
    requireActive();
    return queries.selectProjection(table, projectionType);
  }

  @Override
  public <E> int insert(EntityMeta<E> entity, E value) {
    requireActive();
    return mutations.insert(entity, value);
  }

  @Override
  public <E> int insert(EntityMeta<E> entity, E value, ExecutionOptions executionOptions) {
    requireActive();
    return mutations.insert(entity, value, executionOptions);
  }

  @Override
  public <E> int updateById(EntityMeta<E> entity, E value) {
    requireActive();
    return mutations.updateById(entity, value);
  }

  @Override
  public <E> int updateById(EntityMeta<E> entity, E value, ExecutionOptions executionOptions) {
    requireActive();
    return mutations.updateById(entity, value, executionOptions);
  }

  @Override
  public <E> int deleteById(EntityMeta<E> entity, Object id) {
    requireActive();
    return mutations.deleteById(entity, id);
  }

  @Override
  public <E> int deleteById(EntityMeta<E> entity, Object id, ExecutionOptions executionOptions) {
    requireActive();
    return mutations.deleteById(entity, id, executionOptions);
  }

  @Override
  public void afterCommit(Runnable callback) {
    requireActive();
    afterCommitCallbacks.add(Objects.requireNonNull(callback, "callback"));
  }

  @Override
  public void commit() {
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
      throw new TransactionException(
          "transaction committed but an afterCommit callback failed", callbackFailure);
    }
  }

  @Override
  public void rollback() {
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
    afterCommitCallbacks.clear();
    transaction.close();
  }

  private void requireActive() {
    if (!active()) {
      throw new TransactionException("SKIS session is no longer active");
    }
  }
}
