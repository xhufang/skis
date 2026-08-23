package io.skis.core;

/**
 * Services and execution-scoped options made available while SKIS performs JDBC work.
 *
 * <p>The initial JDBC slice intentionally keeps this contract empty. Future options must be added
 * as default methods so existing providers remain binary compatible.
 */
public interface ExecutionContext {

  /** Context for executions that do not need additional services or options. */
  ExecutionContext EMPTY = new ExecutionContext() {};
}
