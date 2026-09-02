package io.skis.runtime;

import io.skis.core.ExecutionContext;
import io.skis.core.ExecutionOptions;
import io.skis.jdbc.JdbcExecutor;
import io.skis.jdbc.JdbcTransaction;
import io.skis.metadata.EntityMeta;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.MutationPlanCatalog;
import io.skis.query.NonNullQueryColumn;
import io.skis.query.NullableQueryColumn;
import io.skis.query.NullableSelectFromStep;
import io.skis.query.QueryOperations;
import io.skis.query.QueryPlanCacheStatistics;
import io.skis.query.QueryPlanCatalog;
import io.skis.query.QueryTable;
import io.skis.query.SelectQuery;
import io.skis.query.SelectFromStep;
import java.util.Objects;
import java.util.Optional;

/** Default immutable executor assembled by {@link SkisExecutorFactory}. */
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
  public <E> Optional<E> findById(EntityMeta<E> entity, Object id) {
    return queries.findById(entity, id);
  }

  @Override
  public <E> Optional<E> findById(
      EntityMeta<E> entity, Object id, ExecutionOptions executionOptions) {
    return queries.findById(entity, id, executionOptions);
  }

  @Override
  public <E> SelectQuery<E, E> selectFrom(QueryTable<E> table) {
    return queries.selectFrom(table);
  }

  @Override
  public <E, V> SelectFromStep<E, V> select(NonNullQueryColumn<E, V> column) {
    return queries.select(column);
  }

  @Override
  public <E, V> NullableSelectFromStep<E, V> select(NullableQueryColumn<E, V> column) {
    return queries.select(column);
  }

  @Override
  public <E, R> SelectQuery<E, R> selectProjection(QueryTable<E> table, Class<R> projectionType) {
    return queries.selectProjection(table, projectionType);
  }

  @Override
  public <E> int insert(EntityMeta<E> entity, E value) {
    return mutations.insert(entity, value);
  }

  @Override
  public <E> int insert(EntityMeta<E> entity, E value, ExecutionOptions executionOptions) {
    return mutations.insert(entity, value, executionOptions);
  }

  @Override
  public <E> int updateById(EntityMeta<E> entity, E value) {
    return mutations.updateById(entity, value);
  }

  @Override
  public <E> int updateById(EntityMeta<E> entity, E value, ExecutionOptions executionOptions) {
    return mutations.updateById(entity, value, executionOptions);
  }

  @Override
  public <E> int deleteById(EntityMeta<E> entity, Object id) {
    return mutations.deleteById(entity, id);
  }

  @Override
  public <E> int deleteById(EntityMeta<E> entity, Object id, ExecutionOptions executionOptions) {
    return mutations.deleteById(entity, id, executionOptions);
  }

  @Override
  public SkisSession beginTransaction() {
    return beginTransaction(ExecutionOptions.NONE);
  }

  @Override
  public SkisSession beginTransaction(ExecutionOptions executionOptions) {
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
  public <R> R inTransaction(TransactionCallback<R> callback) {
    return inTransaction(ExecutionOptions.NONE, callback);
  }

  @Override
  public <R> R inTransaction(ExecutionOptions executionOptions, TransactionCallback<R> callback) {
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
