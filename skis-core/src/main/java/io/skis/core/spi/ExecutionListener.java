package io.skis.core.spi;

/**
 * Thread-safe, read-only observer of actual SKIS compilation and execution lifecycles.
 *
 * <p>Callbacks run synchronously in registration order. Implementations must not synchronously
 * re-enter SKIS compilation or execution and cannot mutate SQL, parameters, results, or resources.
 */
public interface ExecutionListener {

  default void compilationCompleted(CompilationCompletedEvent event) {}

  default void operationStarted(OperationStartedEvent event) {}

  default void operationCompleted(OperationCompletedEvent event) {}

  default void statementStarted(StatementStartedEvent event) {}

  default void statementCompleted(StatementCompletedEvent event) {}
}
