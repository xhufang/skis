package io.skis.runtime;

/** Work executed against one explicit, non-thread-safe transaction session. */
@FunctionalInterface
public interface TransactionCallback<R> {

  /** Executes application work; returning normally requests a commit. */
  R execute(SkisSession session);
}
