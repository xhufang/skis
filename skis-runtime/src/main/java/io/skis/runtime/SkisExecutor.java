package io.skis.runtime;

import io.skis.core.ExecutionOptions;
import io.skis.core.TransactionException;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.MutationPlanCacheStatistics;
import io.skis.query.QueryOperations;
import io.skis.query.QueryPlanCacheStatistics;
import org.jspecify.annotations.Nullable;

/**
 * Thread-safe injected facade for all SKIS database operations.
 *
 * <p>The facade exposes query and single-entity mutation Fast Paths. Explicit transaction work is
 * performed through a non-thread-safe {@link SkisSession} while applications keep this executor as
 * their one constructor-injected, thread-safe dependency.
 */
public interface SkisExecutor extends QueryOperations, MutationOperations {

  /**
   * Begins an explicit local JDBC transaction. Closing without completion rolls it back.
   *
   * @throws TransactionException when the configured connection provider delegates transaction
   *     ownership to an external transaction manager, or when the transaction cannot be started
   */
  SkisSession beginTransaction();

  /** Begins a transaction whose defaults overlay executor defaults for all session statements. */
  SkisSession beginTransaction(ExecutionOptions executionOptions);

  /**
   * Executes one local callback transaction, committing on return and, after a failure, attempting
   * a rollback only while the session remains active. A failure with an unknown commit outcome is
   * propagated without a second completion attempt.
   *
   * @throws TransactionException when the configured connection provider delegates transaction
   *     ownership to an external transaction manager, or when begun, commit, or an after-commit
   *     callback fails
   */
  <R extends @Nullable Object> R inTransaction(TransactionCallback<R> callback);

  /** Executes one local callback transaction with session-level statement defaults. */
  <R extends @Nullable Object> R inTransaction(
      ExecutionOptions executionOptions, TransactionCallback<R> callback);

  /** Returns an immutable snapshot of the shared dynamic query-plan cache. */
  QueryPlanCacheStatistics queryPlanCacheStatistics();

  /**
   * Invalidates all shared dynamic query plans without resetting activity counters.
   *
   * <p>Applications with unloadable result-type or plugin class loaders must first quiesce affected
   * query executions and then call this method before dropping the loader.
   */
  void clearQueryPlanCache();

  /** Returns actual activity and occupancy of the shared dynamic mutation-plan cache. */
  MutationPlanCacheStatistics mutationPlanCacheStatistics();

  /** Clears shared mutation plans after quiescing operations that use unloadable mappings. */
  void clearMutationPlanCache();
}
