package io.skis.core.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.OperationCompletedEvent;
import io.skis.core.spi.OperationStartedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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
