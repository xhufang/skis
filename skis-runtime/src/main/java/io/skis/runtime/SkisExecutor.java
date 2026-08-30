package io.skis.runtime;

import io.skis.core.ExecutionOptions;
import io.skis.core.TransactionException;
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

  /**
   * Begins an explicit local JDBC transaction. Closing without completion rolls it back.
   *
   * @throws TransactionException when the configured connection provider delegates transaction
   *     ownership to an external transaction manager, or when the transaction cannot be started
   */
  SkisSession beginTransaction();

  /**
   * Begins a transaction whose defaults overlay the executor defaults for all session statements.
   *
   * <p>The default method preserves compatibility for custom executors that only support empty
   * options.
   */
  default SkisSession beginTransaction(ExecutionOptions executionOptions) {
    java.util.Objects.requireNonNull(executionOptions, "executionOptions");
    if (!executionOptions.isEmpty()) {
      throw new UnsupportedOperationException(
          "this SkisExecutor implementation does not support transaction execution options");
    }
    return beginTransaction();
  }

  /**
   * Executes one local callback transaction, committing on return and, after a failure, attempting
   * a rollback only while the session remains active. A failure with an unknown commit outcome is
   * propagated without a second completion attempt.
   *
   * @throws TransactionException when the configured connection provider delegates transaction
   *     ownership to an external transaction manager, or when begun, commit, or an after-commit
   *     callback fails
   */
  <R> R inTransaction(TransactionCallback<R> callback);

  /** Executes one local callback transaction with session-level statement defaults. */
  default <R> R inTransaction(ExecutionOptions executionOptions, TransactionCallback<R> callback) {
    java.util.Objects.requireNonNull(executionOptions, "executionOptions");
    java.util.Objects.requireNonNull(callback, "callback");
    if (!executionOptions.isEmpty()) {
      throw new UnsupportedOperationException(
          "this SkisExecutor implementation does not support transaction execution options");
    }
    return inTransaction(callback);
  }

  /** Returns an immutable snapshot of the shared dynamic query-plan cache. */
  QueryPlanCacheStatistics queryPlanCacheStatistics();

  /** Explicitly invalidates all shared dynamic query plans. */
  void clearQueryPlanCache();
}
