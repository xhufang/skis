package io.skis.runtime;

import io.skis.jdbc.ConnectionProvider;
import io.skis.jdbc.JdbcTransaction;
import io.skis.metadata.EntityMeta;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.MutationPlanCatalog;
import io.skis.query.EntitySelectQuery;
import io.skis.query.ProjectedSelectQuery;
import io.skis.query.QueryColumn;
import io.skis.query.QueryOperations;
import io.skis.query.QueryPlanCacheStatistics;
import io.skis.query.QueryPlanCatalog;
import io.skis.query.QueryTable;
import io.skis.query.SelectFromStep;
import java.util.Objects;
import java.util.Optional;

/** Default immutable executor assembled by {@link SkisExecutorFactory}. */
final class DefaultSkisExecutor implements SkisExecutor {

  private final QueryOperations queries;
  private final MutationOperations mutations;
  private final ConnectionProvider connectionProvider;
  private final QueryPlanCatalog queryPlans;
  private final MutationPlanCatalog mutationPlans;

  DefaultSkisExecutor(
      QueryOperations queries,
      MutationOperations mutations,
      ConnectionProvider connectionProvider,
      QueryPlanCatalog queryPlans,
      MutationPlanCatalog mutationPlans) {
    this.queries = Objects.requireNonNull(queries, "queries");
    this.mutations = Objects.requireNonNull(mutations, "mutations");
    this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    this.queryPlans = Objects.requireNonNull(queryPlans, "queryPlans");
    this.mutationPlans = Objects.requireNonNull(mutationPlans, "mutationPlans");
  }

  @Override
  public <E> Optional<E> findById(EntityMeta<E> entity, Object id) {
    return queries.findById(entity, id);
  }

  @Override
  public <E> EntitySelectQuery<E> selectFrom(QueryTable<E> table) {
    return queries.selectFrom(table);
  }

  @Override
  public <E, V> SelectFromStep<E, V> select(QueryColumn<E, V> column) {
    return queries.select(column);
  }

  @Override
  public <E, R> ProjectedSelectQuery<E, R> selectProjection(
      QueryTable<E> table, Class<R> projectionType) {
    return queries.selectProjection(table, projectionType);
  }

  @Override
  public <E> int insert(EntityMeta<E> entity, E value) {
    return mutations.insert(entity, value);
  }

  @Override
  public <E> int updateById(EntityMeta<E> entity, E value) {
    return mutations.updateById(entity, value);
  }

  @Override
  public <E> int deleteById(EntityMeta<E> entity, Object id) {
    return mutations.deleteById(entity, id);
  }

  @Override
  public SkisSession beginTransaction() {
    JdbcTransaction transaction = JdbcTransaction.begin(connectionProvider);
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
    Objects.requireNonNull(callback, "callback");
    SkisSession session = beginTransaction();
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
