package io.skis.runtime;

import io.skis.mutation.MutationOperations;
import io.skis.query.QueryOperations;
import io.skis.query.QueryPlanCacheStatistics;

/**
 * Thread-safe injected facade for all SKIS database operations.
 *
 * <p>The facade exposes query and single-entity mutation Fast Paths. Explicit transaction work is
 * performed through a non-thread-safe {@link SkisSession} while applications keep this executor as
 * their one constructor-injected, thread-safe dependency.
 */
public interface SkisExecutor extends QueryOperations, MutationOperations {

  /** Begins an explicit local JDBC transaction. Closing without completion rolls it back. */
  SkisSession beginTransaction();

  /** Executes one callback transaction, committing on return and rolling back on failure. */
  <R> R inTransaction(TransactionCallback<R> callback);

  /** Returns an immutable snapshot of the shared dynamic query-plan cache. */
  QueryPlanCacheStatistics queryPlanCacheStatistics();

  /** Explicitly invalidates all shared dynamic query plans. */
  void clearQueryPlanCache();
}
