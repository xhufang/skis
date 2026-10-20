package io.skis.core.internal;

import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisException;
import io.skis.core.SkisStatementKind;
import io.skis.core.spi.CompilationCompletedEvent;
import io.skis.core.spi.CompilationOrigin;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionOutcome;
import io.skis.core.spi.OperationCompletedEvent;
import io.skis.core.spi.OperationStartedEvent;
import io.skis.core.spi.StatementCompletedEvent;
import io.skis.core.spi.StatementPurpose;
import io.skis.core.spi.StatementStartedEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/** Internal immutable listener snapshot shared by one executor and all of its sessions. */
public final class ExecutionListenerDispatcher {

  private static final String REENTRANT_MESSAGE =
      "SKIS compilation or execution must not be entered from an execution listener callback";
  private static final System.Logger LOGGER =
      System.getLogger(ExecutionListenerDispatcher.class.getName());
  private static final AtomicInteger ACTIVE_CALLBACKS = new AtomicInteger();
  private static final ThreadLocal<@Nullable Boolean> CALLBACK_ACTIVE = new ThreadLocal<>();

  public static final ExecutionListenerDispatcher NONE =
      new ExecutionListenerDispatcher(new ExecutionListener[0]);

  private final ExecutionListener[] listeners;
  private final @Nullable AtomicLong ids;

  private ExecutionListenerDispatcher(ExecutionListener[] listeners) {
    this.listeners = listeners;
    this.ids = listeners.length == 0 ? null : new AtomicLong();
  }

  /** Captures a compact immutable listener snapshot in registration order. */
  public static ExecutionListenerDispatcher of(List<? extends ExecutionListener> listeners) {
    Objects.requireNonNull(listeners, "listeners");
    if (listeners.isEmpty()) {
      return NONE;
    }
    ExecutionListener[] snapshot = new ExecutionListener[listeners.size()];
    for (int index = 0; index < snapshot.length; index++) {
      snapshot[index] = Objects.requireNonNull(listeners.get(index), "listener");
    }
    return new ExecutionListenerDispatcher(snapshot);
  }

  /** Returns whether this dispatcher owns any listeners. */
  public boolean enabled() {
    return listeners.length != 0;
  }

  /** Rejects listener-callback re-entry before compilation, connection access, or JDBC work. */
  public static void rejectReentrantCall() {
    if (ACTIVE_CALLBACKS.get() == 0) {
      return;
    }
    if (Boolean.TRUE.equals(CALLBACK_ACTIVE.get())) {
      throw new IllegalStateException(REENTRANT_MESSAGE);
    }
  }

  /** Starts one observed terminal operation. Callers must first verify {@link #enabled()}. */
  public OperationObservation startOperation(
      ExecutionOperationKind operationKind, String dialectId) {
    rejectReentrantCall();
    if (!enabled()) {
      throw new IllegalStateException("the NONE dispatcher cannot start an observation");
    }
    long startedNanos = System.nanoTime();
    long operationId = nextId();
    OperationObservation observation =
        new OperationObservation(this, operationId, operationKind, dialectId, startedNanos);
    long callbackNanos =
        dispatch(
            Callback.OPERATION_STARTED,
            new OperationStartedEvent(operationId, operationKind, dialectId));
    observation.addCallbackNanos(callbackNanos);
    return observation;
  }

  /** Emits a prewarm compilation event without inventing an operation lifecycle. */
  public void prewarmCompilationCompleted(
      ExecutionOperationKind operationKind,
      StatementPurpose purpose,
      SkisStatementKind statementKind,
      String dialectId,
      long durationNanos,
      @Nullable String sqlFingerprint,
      @Nullable String sqlTemplate,
      @Nullable Throwable failure) {
    if (!enabled()) {
      return;
    }
    FailureSnapshot snapshot = FailureSnapshot.of(failure, SkisDiagnosticStage.COMPILATION);
    dispatch(
        Callback.COMPILATION_COMPLETED,
        new CompilationCompletedEvent(
            CompilationCompletedEvent.NO_OPERATION_ID,
            CompilationOrigin.PREWARM,
            operationKind,
            purpose,
            statementKind,
            dialectId,
            snapshot.outcome(),
            durationNanos,
            sqlFingerprint,
            sqlTemplate,
            snapshot.stage(),
            snapshot.diagnostics()));
  }

  long nextId() {
    AtomicLong sequence = Objects.requireNonNull(ids, "enabled dispatcher ID sequence");
    long next;
    do {
      next = sequence.incrementAndGet();
    } while (next == 0L);
    return next;
  }

  long compilationCompleted(
      OperationObservation operation,
      StatementPurpose purpose,
      SkisStatementKind statementKind,
      long durationNanos,
      @Nullable String sqlFingerprint,
      @Nullable String sqlTemplate,
      @Nullable Throwable failure) {
    FailureSnapshot snapshot = FailureSnapshot.of(failure, SkisDiagnosticStage.COMPILATION);
    return dispatch(
        Callback.COMPILATION_COMPLETED,
        new CompilationCompletedEvent(
            operation.operationId(),
            CompilationOrigin.ON_DEMAND,
            operation.operationKind(),
            purpose,
            statementKind,
            operation.dialectId(),
            snapshot.outcome(),
            durationNanos,
            sqlFingerprint,
            sqlTemplate,
            snapshot.stage(),
            snapshot.diagnostics()));
  }

  void operationCompleted(OperationCompletedEvent event) {
    dispatch(Callback.OPERATION_COMPLETED, event);
  }

  long statementStarted(StatementStartedEvent event) {
    return dispatch(Callback.STATEMENT_STARTED, event);
  }

  long statementCompleted(StatementCompletedEvent event) {
    return dispatch(Callback.STATEMENT_COMPLETED, event);
  }

  static long elapsed(long startedNanos, long callbackNanos) {
    long measured = System.nanoTime() - startedNanos - callbackNanos;
    return Math.max(0L, measured);
  }

  static FailureSnapshot failure(@Nullable Throwable failure, SkisDiagnosticStage fallbackStage) {
    return FailureSnapshot.of(failure, fallbackStage);
  }

  private long dispatch(Callback callback, Object event) {
    long started = System.nanoTime();
    for (ExecutionListener listener : listeners) {
      ACTIVE_CALLBACKS.incrementAndGet();
      CALLBACK_ACTIVE.set(Boolean.TRUE);
      try {
        switch (callback) {
          case COMPILATION_COMPLETED ->
              listener.compilationCompleted((CompilationCompletedEvent) event);
          case OPERATION_STARTED -> listener.operationStarted((OperationStartedEvent) event);
          case OPERATION_COMPLETED -> listener.operationCompleted((OperationCompletedEvent) event);
          case STATEMENT_STARTED -> listener.statementStarted((StatementStartedEvent) event);
          case STATEMENT_COMPLETED -> listener.statementCompleted((StatementCompletedEvent) event);
        }
      } catch (Exception failure) {
        logListenerFailure(callback.methodName, listener, failure);
      } finally {
        CALLBACK_ACTIVE.remove();
        ACTIVE_CALLBACKS.decrementAndGet();
      }
    }
    return Math.max(0L, System.nanoTime() - started);
  }

  private static void logListenerFailure(
      String callback, ExecutionListener listener, Exception failure) {
    try {
      LOGGER.log(
          System.Logger.Level.WARNING,
          "SKIS execution listener callback failed [callback="
              + callback
              + ", listenerClass="
              + listener.getClass().getName()
              + ", failureClass="
              + failure.getClass().getName()
              + "]");
    } catch (Exception ignored) {
      // The isolated safety outlet must never recurse or replace the primary execution result.
    }
  }

  private enum Callback {
    COMPILATION_COMPLETED("compilationCompleted"),
    OPERATION_STARTED("operationStarted"),
    OPERATION_COMPLETED("operationCompleted"),
    STATEMENT_STARTED("statementStarted"),
    STATEMENT_COMPLETED("statementCompleted");

    private final String methodName;

    Callback(String methodName) {
      this.methodName = methodName;
    }
  }

  record FailureSnapshot(
      ExecutionOutcome outcome,
      @Nullable SkisDiagnosticStage stage,
      @Nullable SkisDiagnostics diagnostics) {

    static FailureSnapshot of(@Nullable Throwable failure, SkisDiagnosticStage fallbackStage) {
      Objects.requireNonNull(fallbackStage, "fallbackStage");
      if (failure == null) {
        return new FailureSnapshot(ExecutionOutcome.SUCCEEDED, null, null);
      }
      if (failure instanceof SkisException skisFailure) {
        SkisDiagnostics diagnostics = skisFailure.diagnostics();
        return new FailureSnapshot(ExecutionOutcome.FAILED, diagnostics.stage(), diagnostics);
      }
      return new FailureSnapshot(ExecutionOutcome.FAILED, fallbackStage, null);
    }
  }
}
