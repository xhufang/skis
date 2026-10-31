package io.skis.runtime;

import io.skis.core.ExecutionOptions;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.spi.ExecutionContext;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.internal.JdbcTransaction;
import io.skis.mutation.DeleteWrite;
import io.skis.mutation.EntityById;
import io.skis.mutation.InsertInto;
import io.skis.mutation.MergeInto;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.MutationPlanCacheStatistics;
import io.skis.mutation.UpdateSet;
import io.skis.mutation.internal.MutationPlanCatalog;
import io.skis.query.ExecutableSelectQuery;
import io.skis.query.ExecutableSingleColumnSelect;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryOperations;
import io.skis.query.QueryPlanCacheStatistics;
import io.skis.query.QueryRequest;
import io.skis.query.QueryTable;
import io.skis.query.Row2;
import io.skis.query.Row3;
import io.skis.query.SelectFromStep;
import io.skis.query.Selectable;
import io.skis.query.internal.QueryPlanCatalog;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Default immutable executor assembled by {@link Skis}. */
final class DefaultSkisExecutor implements SkisExecutor {

  private final QueryOperations queries;
  private final MutationOperations mutations;
  private final JdbcExecutor jdbcExecutor;
  private final QueryPlanCatalog queryPlans;
  private final MutationPlanCatalog mutationPlans;

  DefaultSkisExecutor(
      QueryOperations queries,
      MutationOperations mutations,
      JdbcExecutor jdbcExecutor,
      QueryPlanCatalog queryPlans,
      MutationPlanCatalog mutationPlans) {
    this.queries = Objects.requireNonNull(queries, "queries");
    this.mutations = Objects.requireNonNull(mutations, "mutations");
    this.jdbcExecutor = Objects.requireNonNull(jdbcExecutor, "jdbcExecutor");
    this.queryPlans = Objects.requireNonNull(queryPlans, "queryPlans");
    this.mutationPlans = Objects.requireNonNull(mutationPlans, "mutationPlans");
  }

  @Override
  public <E> Optional<E> findById(QueryTable<E> table, Object id) {
    return queries.findById(table, id);
  }

  @Override
  public <E> Optional<E> findById(
      QueryTable<E> table, Object id, ExecutionOptions executionOptions) {
    return queries.findById(table, id, executionOptions);
  }

  @Override
  public <E> ExecutableSelectQuery<E> selectFrom(QueryTable<E> table) {
    return queries.selectFrom(table);
  }

  @Override
  public <E> SelectFromStep<ExecutableSelectQuery<E>> select(QueryTable<E> table) {
    return queries.select(table);
  }

  @Override
  public <E> SelectFromStep<ExecutableSelectQuery<@Nullable E>> selectNullable(
      QueryTable<E> table) {
    return queries.selectNullable(table);
  }

  @Override
  public <V> SelectFromStep<ExecutableSingleColumnSelect<V>> select(
      NonNullSelectable<V> selectable) {
    return queries.select(selectable);
  }

  @Override
  public <V> SelectFromStep<ExecutableSingleColumnSelect<@Nullable V>> select(
      Selectable<V> selectable) {
    return queries.select(selectable);
  }

  @Override
  public <V> SelectFromStep<ExecutableSingleColumnSelect<@Nullable V>> selectNullable(
      NonNullSelectable<V> selectable) {
    return queries.selectNullable(selectable);
  }

  @Override
  public <R> SelectFromStep<ExecutableSelectQuery<R>> select(ProjectionSelection<R> projection) {
    return queries.select(projection);
  }

  @Override
  public <R> SelectFromStep<ExecutableSelectQuery<R>> select(ProjectionType<R> projection) {
    return queries.select(projection);
  }

  @Override
  public <T extends @Nullable Object> T run(QueryRequest<T> request) {
    return queries.run(request);
  }

  @Override
  public <E> InsertInto<E> insertInto(QueryTable<E> table) {
    return mutations.insertInto(table);
  }

  @Override
  public <E> MergeInto<E> mergeInto(QueryTable<E> table) {
    return mutations.mergeInto(table);
  }

  @Override
  public <E> UpdateSet<E> update(QueryTable<E> table) {
    return mutations.update(table);
  }

  @Override
  public <E> DeleteWrite<E> deleteFrom(QueryTable<E> table) {
    return mutations.deleteFrom(table);
  }

  @Override
  public <E> EntityById<E> updateById(QueryTable<E> table) {
    return mutations.updateById(table);
  }

  @Override
  public <E> EntityById<E> deleteById(QueryTable<E> table) {
    return mutations.deleteById(table);
  }

  @Override
  public <E> int deleteById(QueryTable<E> table, Object id) {
    return mutations.deleteById(table, id);
  }

  @Override
  public <E> int deleteById(QueryTable<E> table, Object id, ExecutionOptions executionOptions) {
    return mutations.deleteById(table, id, executionOptions);
  }

  @Override
  public SkisSession beginTransaction() {
    return beginTransaction(ExecutionOptions.NONE);
  }

  @Override
  public SkisSession beginTransaction(ExecutionOptions executionOptions) {
    ExecutionListenerDispatcher.rejectReentrantCall();
    JdbcExecutor sessionExecutor =
        jdbcExecutor.withDefaultExecutionOptions(
            Objects.requireNonNull(executionOptions, "executionOptions"));
    JdbcTransaction transaction =
        JdbcTransaction.beginWithExecutor(sessionExecutor, ExecutionContext.EMPTY);
    try {
      QueryOperations sessionQueries = queryPlans.bind(transaction.jdbcExecutor());
      MutationOperations sessionMutations =
          mutationPlans.bind(transaction.jdbcExecutor(), transaction::active);
      return new DefaultSkisSession(transaction, sessionQueries, sessionMutations);
    } catch (RuntimeException | Error failure) {
      try {
        transaction.close();
      } catch (RuntimeException | Error closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  @Override
  public <R extends @Nullable Object> R inTransaction(TransactionCallback<R> callback) {
    return inTransaction(ExecutionOptions.NONE, callback);
  }

  @Override
  public <R extends @Nullable Object> R inTransaction(
      ExecutionOptions executionOptions, TransactionCallback<R> callback) {
    Objects.requireNonNull(callback, "callback");
    SkisSession session = beginTransaction(executionOptions);
    Throwable pendingFailure = null;
    try {
      R result = callback.execute(session);
      session.commit();
      return result;
    } catch (RuntimeException | Error failure) {
      pendingFailure = failure;
      if (session.active()) {
        try {
          session.rollback();
        } catch (RuntimeException | Error rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
      }
      throw failure;
    } finally {
      try {
        session.close();
      } catch (RuntimeException | Error closeFailure) {
        if (pendingFailure != null) {
          pendingFailure.addSuppressed(closeFailure);
        } else {
          throw closeFailure;
        }
      }
    }
  }

  @Override
  public QueryPlanCacheStatistics queryPlanCacheStatistics() {
    return queryPlans.projectionPlanCacheStatistics();
  }

  @Override
  public void clearQueryPlanCache() {
    queryPlans.clearProjectionPlans();
  }

  @Override
  public MutationPlanCacheStatistics mutationPlanCacheStatistics() {
    return mutationPlans.planCacheStatistics();
  }

  @Override
  public void clearMutationPlanCache() {
    mutationPlans.clearDynamicPlans();
  }

  @Override
  public <A, B> SelectFromStep<ExecutableSelectQuery<Row2<@Nullable A, @Nullable B>>> select(
      Selectable<A> first, Selectable<B> second) {
    return queries.select(first, second);
  }

  @Override
  public <A, B> SelectFromStep<ExecutableSelectQuery<Row2<A, @Nullable B>>> select(
      NonNullSelectable<A> first, Selectable<B> second) {
    return queries.select(first, second);
  }

  @Override
  public <A, B> SelectFromStep<ExecutableSelectQuery<Row2<@Nullable A, B>>> select(
      Selectable<A> first, NonNullSelectable<B> second) {
    return queries.select(first, second);
  }

  @Override
  public <A, B> SelectFromStep<ExecutableSelectQuery<Row2<A, B>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second) {
    return queries.select(first, second);
  }

  @Override
  public <A, B, C>
      SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, @Nullable B, @Nullable C>>> select(
          Selectable<A> first, Selectable<B> second, Selectable<C> third) {
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, @Nullable B, @Nullable C>>> select(
      NonNullSelectable<A> first, Selectable<B> second, Selectable<C> third) {
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, B, @Nullable C>>> select(
      Selectable<A> first, NonNullSelectable<B> second, Selectable<C> third) {
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, B, @Nullable C>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second, Selectable<C> third) {
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, @Nullable B, C>>> select(
      Selectable<A> first, Selectable<B> second, NonNullSelectable<C> third) {
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, @Nullable B, C>>> select(
      NonNullSelectable<A> first, Selectable<B> second, NonNullSelectable<C> third) {
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, B, C>>> select(
      Selectable<A> first, NonNullSelectable<B> second, NonNullSelectable<C> third) {
    return queries.select(first, second, third);
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, B, C>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second, NonNullSelectable<C> third) {
    return queries.select(first, second, third);
  }
}
