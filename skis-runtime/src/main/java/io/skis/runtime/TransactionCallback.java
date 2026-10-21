package io.skis.runtime;

import org.jspecify.annotations.Nullable;

/** Work executed against one explicit, non-thread-safe transaction session. */
@FunctionalInterface
public interface TransactionCallback<R extends @Nullable Object> {

  /** Executes application work; {@code R} carries nullability and normal return requests a commit. */
  R execute(SkisSession session);
}
