package io.skis.core.internal;

import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisException;
import io.skis.core.SkisStatementKind;
import io.skis.core.SqlLoggingOptions;
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
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Internal immutable listener snapshot shared by one executor and all of its sessions. */
public final class ExecutionListenerDispatcher {

  private static final String REENTRANT_MESSAGE =
      "SKIS compilation or execution must not be entered from an execution listener callback";
  private static final Logger LOGGER = LoggerFactory.getLogger("io.skis.internal");
  // Monotonic gate: avoid ThreadLocal access until the first callback is used.
  private static volatile boolean callbackGuardUsed;
  private static final ThreadLocal<@Nullable Boolean> CALLBACK_ACTIVE = new ThreadLocal<>();

  public static final ExecutionListenerDispatcher NONE =
      new ExecutionListenerDispatcher(new ExecutionListener[0]);

  private final ExecutionListener[] listeners;
  private final @Nullable AtomicLong ids;
  private final @Nullable SqlLoggingListener logging;
  private final ExecutionListenerDispatcher @Nullable [] loggingSnapshots;

  private ExecutionListenerDispatcher(ExecutionListener[] listeners) {
    this(listeners, listeners.length == 0 ? null : new AtomicLong(), null);
  }

  private ExecutionListenerDispatcher(
      ExecutionListener[] listeners, @Nullable AtomicLong ids, @Nullable SqlLoggingListener logging) {
    this(listeners, ids, logging, null);
  }

  private ExecutionListenerDispatcher(
      ExecutionListener[] listeners,
      @Nullable AtomicLong ids,
      @Nullable SqlLoggingListener logging,
      ExecutionListenerDispatcher @Nullable [] loggingSnapshots) {
    this.listeners = listeners;
    this.ids = ids;
    this.logging = logging;
    this.loggingSnapshots = loggingSnapshots;
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

  /** Adds opt-in value-free logging without enabling parameter summaries or changing listeners. */
  public static ExecutionListenerDispatcher of(
      List<? extends ExecutionListener> listeners, SqlLoggingOptions options) {
    Objects.requireNonNull(options, "options");
    ExecutionListenerDispatcher users = of(listeners);
    if (!options.sql() && options.slowQueryThreshold() == null) {
      return users;
    }
    AtomicLong sequence = users.ids == null ? new AtomicLong() : users.ids;
    SqlLoggingListener logging = new SqlLoggingListener(options);
    ExecutionListenerDispatcher disabled = users.ids == null
        ? new ExecutionListenerDispatcher(users.listeners, sequence, null) : users;
    ExecutionListenerDispatcher sql = options.sql()
        ? new ExecutionListenerDispatcher(
            users.listeners, sequence, logging.snapshot(SqlLoggingListener.SQL_DEMAND)) : disabled;
    ExecutionListenerDispatcher slow = options.slowQueryThreshold() != null
        ? new ExecutionListenerDispatcher(
            users.listeners, sequence, logging.snapshot(SqlLoggingListener.SLOW_DEMAND)) : disabled;
    ExecutionListenerDispatcher both = options.sql() && options.slowQueryThreshold() != null
        ? new ExecutionListenerDispatcher(users.listeners, sequence,
            logging.snapshot(SqlLoggingListener.SQL_DEMAND | SqlLoggingListener.SLOW_DEMAND))
        : options.sql() ? sql : slow;
    return new ExecutionListenerDispatcher(users.listeners, sequence, logging,
        new ExecutionListenerDispatcher[] {disabled, sql, slow, both});
  }

  /** Whether user listeners or currently enabled value-free logging need observations. */
  public boolean enabled() {
    return listeners.length != 0 || (logging != null && logging.enabled());
  }

  /** Rejects listener/logging re-entry before compilation, connection access, or JDBC work. */
  public static void rejectReentrantCall() {
    if (callbackGuardUsed && Boolean.TRUE.equals(CALLBACK_ACTIVE.get())) {
      throw new IllegalStateException(REENTRANT_MESSAGE);
    }
  }

  /** Shares the callback guard with logging, preserving an enclosing listener's ownership. */
  static boolean enterCallback() {
    if (!callbackGuardUsed) {
      callbackGuardUsed = true;
    }
    if (Boolean.TRUE.equals(CALLBACK_ACTIVE.get())) {
      return false;
    }
    CALLBACK_ACTIVE.set(Boolean.TRUE);
    return true;
  }

  static void exitCallback(boolean entered) {
    if (entered) {
      // Reuse the entry across probes/callbacks; it never holds invocation data or resources.
      CALLBACK_ACTIVE.set(Boolean.FALSE);
    }
  }

  /** Captures demand once; returns {@code null} before allocating or timing an unobserved operation. */
  public @Nullable OperationObservation startOperation(
      ExecutionOperationKind operationKind, String dialectId) {
    rejectReentrantCall();
    ExecutionListenerDispatcher active = logging == null || loggingSnapshots == null ? this
        : loggingSnapshots[logging.demand()];
    if (active.listeners.length == 0 && active.logging == null) {
      return null;
    }
    long startedNanos = System.nanoTime();
    long operationId = nextId();
    OperationObservation observation =
        new OperationObservation(active, operationId, operationKind, dialectId, startedNanos);
    long callbackNanos =
        active.dispatch(
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
      boolean entered = enterCallback();
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
        exitCallback(entered);
      }
    }
    if (logging != null) {
      // SafeLog guards backend re-entry and isolates ordinary failures for these value-free events.
      switch (callback) {
        case STATEMENT_STARTED -> logging.statementStarted((StatementStartedEvent) event);
        case STATEMENT_COMPLETED -> logging.statementCompleted((StatementCompletedEvent) event);
        default -> { }
      }
    }
    return Math.max(0L, System.nanoTime() - started);
  }

  private static void logListenerFailure(
      String callback, ExecutionListener listener, Exception failure) {
    try {
      SafeLog.warn(LOGGER,
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
