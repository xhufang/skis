package io.skis.runtime;

import io.skis.core.ExecutionOptions;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.spi.ExecutionContext;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.internal.JdbcTransaction;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.internal.MutationPlanCatalog;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryOperations;
import io.skis.query.QueryPlanCacheStatistics;
import io.skis.query.QueryPlanCatalog;
import io.skis.query.QueryTable;
import io.skis.query.SelectDescription;
import io.skis.query.SelectFromStep;
import io.skis.query.SelectQuery;
import io.skis.query.Selectable;
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
  public <E> SelectQuery<E> selectFrom(QueryTable<E> table) {
    return queries.selectFrom(table);
  }

  @Override
  public <E> SelectFromStep<SelectQuery<E>> select(QueryTable<E> table) {
    return queries.select(table);
  }

  @Override
  public <E> SelectFromStep<SelectQuery<@Nullable E>> selectNullable(QueryTable<E> table) {
    return queries.selectNullable(table);
  }

  @Override
  public <V> SelectFromStep<SelectQuery<V>> select(NonNullSelectable<V> selectable) {
    return queries.select(selectable);
  }

  @Override
  public <V> SelectFromStep<SelectQuery<@Nullable V>> select(Selectable<V> selectable) {
    return queries.select(selectable);
  }

  @Override
  public <V> SelectFromStep<SelectQuery<@Nullable V>> selectNullable(
      NonNullSelectable<V> selectable) {
    return queries.selectNullable(selectable);
  }

  @Override
  public <R> SelectFromStep<SelectQuery<R>> select(ProjectionSelection<R> projection) {
    return queries.select(projection);
  }

  @Override
  public <R> SelectFromStep<SelectQuery<R>> select(ProjectionType<R> projection) {
    return queries.select(projection);
  }

  @Override
  public <R extends @Nullable Object> SelectQuery<R> query(SelectDescription<R> description) {
    return queries.query(description);
  }

  @Override
  public <E> int insert(QueryTable<E> table, E value) {
    return mutations.insert(table, value);
  }

  @Override
  public <E> int insert(QueryTable<E> table, E value, ExecutionOptions executionOptions) {
    return mutations.insert(table, value, executionOptions);
  }

  @Override
  public <E> int updateById(QueryTable<E> table, E value) {
    return mutations.updateById(table, value);
  }

  @Override
  public <E> int updateById(QueryTable<E> table, E value, ExecutionOptions executionOptions) {
    return mutations.updateById(table, value, executionOptions);
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
      MutationOperations sessionMutations = mutationPlans.bind(transaction.jdbcExecutor());
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
}
