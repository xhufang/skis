package io.skis.query.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.OperationStartedEvent;
import io.skis.jdbc.internal.JdbcCursor;
import io.skis.query.PageRequest;
import io.skis.query.QueryCursor;
import io.skis.query.QueryParameter;
import io.skis.query.SliceRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Verifies query-side pagination, continuation, and resource-thread diagnostic codes. */
class QueryDiagnosticContractTest {

  @Test
  void paginationRequestsReportThePaginationFamily() {
    assertEquals(
        SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED,
        assertThrows(QueryValidationException.class, () -> PageRequest.page(-1, 10)).code());
    assertEquals(
        SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED,
        assertThrows(QueryValidationException.class, () -> PageRequest.page(0, 0)).code());
    assertEquals(
        SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED,
        assertThrows(QueryValidationException.class, () -> SliceRequest.offset(-1, 10)).code());
    assertEquals(
        SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED,
        assertThrows(QueryValidationException.class, () -> SliceRequest.keysetFirst(0)).code());
  }

  @Test
  void continuationConstructionReportsTheContinuationFamily() {
    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () -> DefaultSliceContinuation.offset("fingerprint", "signature", -1, "digest"));

    assertEquals(SkisErrorCode.QUERY_CONTINUATION_INVALID, failure.code());
  }

  @Test
  void cursorUseOutsideItsResourceOwningThreadIsAUsageFailureNotADatabaseFailure()
      throws Exception {
    QueryCursor<String> cursor = new DefaultQueryCursor<>(delegate(), true);
    AtomicReference<@Nullable Throwable> observed = new AtomicReference<>();
    Thread other = new Thread(() -> cursor.advance(), "foreign-cursor-thread");
    other.setUncaughtExceptionHandler((ignored, failure) -> observed.set(failure));

    other.start();
    other.join();

    QueryValidationException failure =
        assertInstanceOf(QueryValidationException.class, observed.get());
    assertEquals(SkisErrorCode.QUERY_CURSOR_THREAD, failure.code());
  }

  @Test
  void neverInvokesToStringOnABoundParameterValue() {
    QueryParameter<Object> parameter = DefaultQueryParameter.anonymousNonNull(Object.class);
    QueryParameters values = QueryParameters.of(parameter, new ThrowingToString());
    List<QueryParameter<?>> parameters = List.of(parameter);

    QueryArguments projected = values.valuesFor(parameters);

    assertEquals(1, projected.size());
    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class, () -> QueryParameters.empty().validateFor(parameters));
    assertEquals(SkisErrorCode.QUERY_PARAMETER_MISSING, failure.code());
  }

  @Test
  void listenerCannotReadOrCloseAnExistingCursorButCanInspectItsClosedState() {
    AtomicInteger resourceCalls = new AtomicInteger();
    QueryCursor<String> cursor =
        new DefaultQueryCursor<>(
            new JdbcCursor<>() {
              @Override
              public boolean advance() {
                resourceCalls.incrementAndGet();
                return true;
              }

              @Override
              public String current() {
                resourceCalls.incrementAndGet();
                return "row";
              }

              @Override
              public boolean isClosed() {
                return false;
              }

              @Override
              public void close() {
                resourceCalls.incrementAndGet();
              }
            },
            true);
    ExecutionListener listener =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            assertThrows(IllegalStateException.class, cursor::advance);
            assertThrows(IllegalStateException.class, cursor::current);
            assertThrows(IllegalStateException.class, cursor::close);
            assertFalse(cursor.isClosed());
            assertEquals(0, resourceCalls.get());
          }
        };

    OperationObservation observation =
        ExecutionListenerDispatcher.of(List.of(listener))
            .startOperation(ExecutionOperationKind.FETCH_LIST, "test");

    assertEquals(0, resourceCalls.get());
    assertTrue(cursor.advance());
    assertEquals("row", cursor.current());
    cursor.close();
    assertEquals(3, resourceCalls.get());
    observation.completeSucceeded(ExecutionTermination.COMPLETED);
  }

  private static JdbcCursor<String> delegate() {
    return new JdbcCursor<>() {
      @Override
      public boolean advance() {
        return true;
      }

      @Override
      public String current() {
        return "row";
      }

      @Override
      public boolean isClosed() {
        return false;
      }

      @Override
      public void close() {}
    };
  }

  private static final class ThrowingToString {

    @Override
    public String toString() {
      throw new AssertionError("framework diagnostics must not call value toString()");
    }
  }
}
