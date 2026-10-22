package io.skis.core.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisErrorCode;
import io.skis.core.SkisStatementKind;
import io.skis.core.spi.CompilationCompletedEvent;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.OperationCompletedEvent;
import io.skis.core.spi.OperationStartedEvent;
import io.skis.core.spi.StatementCompletedEvent;
import io.skis.core.spi.StatementPurpose;
import io.skis.core.spi.StatementStartedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExecutionListenerDispatcherTest {

  @Test
  void preservesRegistrationOrderAndIsolatesOrdinaryListenerExceptions() {
    List<String> callbacks = new ArrayList<>();
    ExecutionListener first =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            callbacks.add("first-start");
            throw new IllegalStateException("listener-only detail");
          }

          @Override
          public void operationCompleted(OperationCompletedEvent event) {
            callbacks.add("first-complete");
          }
        };
    ExecutionListener second =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            callbacks.add("second-start");
          }

          @Override
          public void operationCompleted(OperationCompletedEvent event) {
            callbacks.add("second-complete");
          }
        };
    ExecutionListenerDispatcher dispatcher = ExecutionListenerDispatcher.of(List.of(first, second));

    OperationObservation observation =
        dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, "test");
    observation.completeSucceeded(ExecutionTermination.COMPLETED);

    assertEquals(
        List.of("first-start", "second-start", "first-complete", "second-complete"), callbacks);
  }

  @Test
  void isolatesFailuresInEveryCallbackAndPreservesThePrimaryFailureDiagnostics() {
    List<String> callbacks = new ArrayList<>();
    QueryValidationException primary =
        QueryValidationException.of(SkisErrorCode.QUERY_RESULT_MAPPING, "row mapping failed");
    ExecutionListener failing =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            throw new IllegalStateException("operation-start listener");
          }

          @Override
          public void compilationCompleted(CompilationCompletedEvent event) {
            throw new IllegalStateException("compilation listener");
          }

          @Override
          public void statementStarted(StatementStartedEvent event) {
            throw new IllegalStateException("statement-start listener");
          }

          @Override
          public void statementCompleted(StatementCompletedEvent event) {
            throw new IllegalStateException("statement-complete listener");
          }

          @Override
          public void operationCompleted(OperationCompletedEvent event) {
            throw new IllegalStateException("operation-complete listener");
          }
        };
    ExecutionListener recording =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            callbacks.add("operation-start");
          }

          @Override
          public void compilationCompleted(CompilationCompletedEvent event) {
            callbacks.add("compile");
          }

          @Override
          public void statementStarted(StatementStartedEvent event) {
            callbacks.add("statement-start");
          }

          @Override
          public void statementCompleted(StatementCompletedEvent event) {
            callbacks.add("statement-complete");
            assertSame(primary.diagnostics(), event.diagnostics());
            assertEquals(ExecutionTermination.FAILED, event.termination());
          }

          @Override
          public void operationCompleted(OperationCompletedEvent event) {
            callbacks.add("operation-complete");
            assertSame(primary.diagnostics(), event.diagnostics());
            assertEquals(ExecutionTermination.FAILED, event.termination());
          }
        };
    OperationObservation operation =
        ExecutionListenerDispatcher.of(List.of(failing, recording))
            .startOperation(ExecutionOperationKind.FETCH_LIST, "test");
    operation.compilationCompleted(
        StatementPurpose.PRIMARY, SkisStatementKind.SELECT, 1L, "fingerprint", "SELECT 1", null);
    StatementObservation statement =
        operation.startStatement(
            StatementPurpose.PRIMARY, SkisStatementKind.SELECT, "test", "fingerprint", "SELECT 1");
    statement.completeFailed(
        primary, SkisDiagnosticStage.EXECUTION, 0, StatementCompletedEvent.UNKNOWN_ROW_COUNT);
    operation.completeFailed(primary, SkisDiagnosticStage.EXECUTION);
    // Retrying completion after a failed listener must not notify twice.
    statement.completeFailed(
        primary, SkisDiagnosticStage.EXECUTION, 0, StatementCompletedEvent.UNKNOWN_ROW_COUNT);
    operation.completeFailed(primary, SkisDiagnosticStage.EXECUTION);

    assertEquals(
        List.of("operation-start", "compile", "statement-start", "statement-complete", "operation-complete"),
        callbacks);
    assertEquals(0, primary.getSuppressed().length);
    assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
  }

  @Test
  void rejectsSynchronousReentryAndRemovesTheMarkerAfterDispatch() {
    AtomicReference<IllegalStateException> rejection = new AtomicReference<>();
    ExecutionListener listener =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            try {
              ExecutionListenerDispatcher.rejectReentrantCall();
            } catch (IllegalStateException failure) {
              rejection.set(failure);
            }
          }
        };
    ExecutionListenerDispatcher dispatcher = ExecutionListenerDispatcher.of(List.of(listener));

    dispatcher.startOperation(ExecutionOperationKind.FETCH_ONE, "test");

    assertNotNull(rejection.get());
    assertEquals(
        "SKIS compilation or execution must not be entered from an execution listener callback",
        rejection.get().getMessage());
    assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
  }

  @Test
  @Timeout(10)
  void allowsAnotherThreadWhileAListenerCallbackIsActive() throws Exception {
    CountDownLatch callbackStarted = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    ExecutionListener listener =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            callbackStarted.countDown();
            try {
              assertTrue(releaseCallback.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new AssertionError("listener callback was interrupted", failure);
            }
          }
        };
    ExecutionListenerDispatcher dispatcher = ExecutionListenerDispatcher.of(List.of(listener));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<OperationObservation> observed =
          executor.submit(() -> dispatcher.startOperation(ExecutionOperationKind.FETCH_ONE, "test"));
      assertTrue(callbackStarted.await(5, TimeUnit.SECONDS));
      // A callback on another thread must not reject an independent caller as reentrant.
      assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
      releaseCallback.countDown();
      observed.get(5, TimeUnit.SECONDS).completeSucceeded(ExecutionTermination.COMPLETED);
      assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
    } finally {
      releaseCallback.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void propagatesErrorsButStillClearsTheReentryMarker() {
    ExecutionListener listener =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            throw new AssertionError("fatal-listener-error");
          }
        };
    ExecutionListenerDispatcher dispatcher = ExecutionListenerDispatcher.of(List.of(listener));

    assertThrows(
        AssertionError.class,
        () -> dispatcher.startOperation(ExecutionOperationKind.FETCH_ONE, "test"));
    assertDoesNotThrow(ExecutionListenerDispatcher::rejectReentrantCall);
  }

  @Test
  void keepsConcurrentOperationCorrelationIndependent() throws Exception {
    Set<Long> started = ConcurrentHashMap.newKeySet();
    Set<Long> completed = ConcurrentHashMap.newKeySet();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    ExecutionListener listener =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            started.add(event.operationId());
          }

          @Override
          public void operationCompleted(OperationCompletedEvent event) {
            completed.add(event.operationId());
          }
        };
    ExecutionListenerDispatcher dispatcher = ExecutionListenerDispatcher.of(List.of(listener));
    Runnable execution =
        () -> {
          try {
            OperationObservation observation =
                dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, "test");
            observation.completeSucceeded(ExecutionTermination.COMPLETED);
          } catch (Throwable thrown) {
            failure.compareAndSet(null, thrown);
          }
        };
    Thread first = new Thread(execution);
    Thread second = new Thread(execution);

    first.start();
    second.start();
    first.join();
    second.join();

    assertNull(failure.get());
    assertEquals(2, started.size());
    assertEquals(started, completed);
  }
}
