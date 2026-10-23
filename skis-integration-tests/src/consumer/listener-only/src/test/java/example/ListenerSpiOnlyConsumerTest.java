package example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.skis.core.SkisStatementKind;
import io.skis.core.SqlLoggingOptions;
import io.skis.core.spi.ParameterRedactor;
import io.skis.core.spi.CompilationCompletedEvent;
import io.skis.core.spi.CompilationOrigin;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionOutcome;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.OperationCompletedEvent;
import io.skis.core.spi.OperationStartedEvent;
import io.skis.core.spi.StatementCompletedEvent;
import io.skis.core.spi.StatementPurpose;
import io.skis.core.spi.StatementStartedEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ListenerSpiOnlyConsumerTest {

  @Test
  void configuresTheCoreRedactionContractWithoutRuntimeOrALoggingProvider() {
    ParameterRedactor policy = (index, type, value) -> "approved";
    SqlLoggingOptions options = SqlLoggingOptions.builder().parameterRedactor(policy).build();
    assertEquals(false, options.parameters());
    assertEquals("approved", options.parameterRedactor().redact(1, String.class, "secret"));
    assertEquals("java.lang.String", ParameterRedactor.TYPE_ONLY.redact(1, String.class, "secret"));
  }

  @Test
  void implementsEveryCallbackWithOnlyTheCoreArtifact() {
    List<Object> events = new CopyOnWriteArrayList<>();
    ExecutionListener listener = new ExternalListener(events);
    OperationStartedEvent started =
        new OperationStartedEvent(1L, ExecutionOperationKind.FETCH_LIST, "external");
    CompilationCompletedEvent compiled =
        new CompilationCompletedEvent(
            1L, CompilationOrigin.ON_DEMAND, ExecutionOperationKind.FETCH_LIST,
            StatementPurpose.PRIMARY, SkisStatementKind.SELECT, "external",
            ExecutionOutcome.SUCCEEDED, 0L, "fingerprint", "SELECT ?", null, null);
    StatementStartedEvent sqlStarted =
        new StatementStartedEvent(
            1L, 2L, ExecutionOperationKind.FETCH_LIST, StatementPurpose.PRIMARY,
            SkisStatementKind.SELECT, "external", "fingerprint", "SELECT ?");
    StatementCompletedEvent sqlCompleted =
        new StatementCompletedEvent(
            1L, 2L, ExecutionOperationKind.FETCH_LIST, StatementPurpose.PRIMARY,
            SkisStatementKind.SELECT, "external", "fingerprint", "SELECT ?",
            ExecutionOutcome.SUCCEEDED, ExecutionTermination.COMPLETED, 0L, 0L, 1L,
            StatementCompletedEvent.UNKNOWN_ROW_COUNT, null, null);
    OperationCompletedEvent completed =
        new OperationCompletedEvent(
            1L, ExecutionOperationKind.FETCH_LIST, "external", ExecutionOutcome.SUCCEEDED,
            ExecutionTermination.COMPLETED, 0L, null, null);

    listener.operationStarted(started);
    listener.compilationCompleted(compiled);
    listener.statementStarted(sqlStarted);
    listener.statementCompleted(sqlCompleted);
    listener.operationCompleted(completed);

    assertEquals(List.of(started, compiled, sqlStarted, sqlCompleted, completed), events);
  }

  private record ExternalListener(List<Object> events) implements ExecutionListener {

    @Override
    public void compilationCompleted(CompilationCompletedEvent event) {
      events.add(event);
    }

    @Override
    public void operationStarted(OperationStartedEvent event) {
      events.add(event);
    }

    @Override
    public void operationCompleted(OperationCompletedEvent event) {
      events.add(event);
    }

    @Override
    public void statementStarted(StatementStartedEvent event) {
      events.add(event);
    }

    @Override
    public void statementCompleted(StatementCompletedEvent event) {
      events.add(event);
    }
  }
}
