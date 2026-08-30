package io.skis.core;

/**
 * Services and execution-scoped options made available while SKIS performs JDBC work.
 *
 * <p>Execution options are exposed through default methods so existing connection providers remain
 * binary compatible. Future services must follow the same compatibility rule.
 */
public interface ExecutionContext {

  /** Context for executions that do not need additional services or options. */
  ExecutionContext EMPTY = new ExecutionContext() {};

  /** Returns immutable per-statement overrides; empty options inherit executor defaults. */
  default ExecutionOptions executionOptions() {
    return ExecutionOptions.NONE;
  }

  /** Creates a context that exposes the supplied immutable statement options. */
  static ExecutionContext of(ExecutionOptions executionOptions) {
    ExecutionOptions options =
        java.util.Objects.requireNonNull(executionOptions, "executionOptions");
    return options.isEmpty() ? EMPTY : options.executionContext();
  }
}

record OptionsExecutionContext(ExecutionOptions executionOptions) implements ExecutionContext {}
