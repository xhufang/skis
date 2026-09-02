package io.skis.runtime;

import io.skis.mutation.MutationOperations;
import io.skis.query.QueryOperations;

/** Explicit non-thread-safe transaction session owning one JDBC connection. */
public interface SkisSession extends QueryOperations, MutationOperations, AutoCloseable {

  /**
   * Registers work that runs only after the JDBC commit succeeds.
   *
   * <p>Callbacks run in registration order. A callback failure does not roll back the already
   * committed transaction; remaining callbacks are still attempted and their failures are retained.
   */
  void afterCommit(Runnable callback);

  /** Commits this active session and then runs its registered after-commit callbacks. */
  void commit();

  /** Rolls back this active session. */
  void rollback();

  /** Returns whether this session still accepts operations. */
  boolean active();

  /** Rolls back an uncompleted session and releases its connection. */
  @Override
  void close();
}
