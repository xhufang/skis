package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import example.skis.PetTable;
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
import io.skis.dialect.h2.H2Dialect;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.query.SelectQuery;
import io.skis.query.SliceRequest;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class ExecutionListenerConsumerTest {

  @Test
  void observesPageAndSliceWithSafeSnapshotsAndSilentSharedCacheHits() throws Exception {
    JdbcDataSource dataSource = dataSource();
    Recorder recorder = new Recorder("only", new CopyOnWriteArrayList<>());
    SkisExecutor executor =
        Skis.builder(dataSource).dialect(H2Dialect.INSTANCE).listener(recorder).build();
    seed(executor);
    recorder.events.clear();
    PetTable pet = PetTable.petTable;
    SelectQuery<Pet> query =
        executor.selectFrom(pet).where(pet.id().in(List.of(1L, 2L, 3L))).orderBy(pet.id().asc());

    Page<Pet> page = query.fetchPage(PageRequest.page(0, 2));

    assertEquals(3L, page.totalElements());
    assertEquals(List.of(1L, 2L), page.items().stream().map(Pet::id).toList());
    assertEquals(1, recorder.events(OperationStartedEvent.class).size());
    assertEquals(1, recorder.events(OperationCompletedEvent.class).size());
    List<StatementCompletedEvent> statements = recorder.events(StatementCompletedEvent.class);
    assertEquals(
        List.of(StatementPurpose.PAGE_CONTENT, StatementPurpose.PAGE_COUNT),
        statements.stream().map(StatementCompletedEvent::purpose).toList());
    assertEquals(
        List.of(2L, 1L), statements.stream().map(StatementCompletedEvent::readRowCount).toList());
    assertEquals(
        2L, statements.stream().map(StatementCompletedEvent::statementId).distinct().count());
    long operationId = recorder.events(OperationStartedEvent.class).getFirst().operationId();
    for (StatementStartedEvent event : recorder.events(StatementStartedEvent.class)) {
      assertEquals(operationId, event.operationId());
    }
    for (StatementCompletedEvent event : statements) {
      assertEquals(operationId, event.operationId());
      assertEquals(ExecutionOutcome.SUCCEEDED, event.outcome());
      assertTrue(event.sqlTemplate().contains("?"));
      assertFalse(event.toString().contains("sensitive-pet"));
    }
    List<CompilationCompletedEvent> compilations = recorder.events(CompilationCompletedEvent.class);
    assertEquals(2, compilations.size());
    assertTrue(
        compilations.stream().allMatch(event -> event.origin() == CompilationOrigin.ON_DEMAND));
    assertTrue(compilations.stream().allMatch(event -> event.operationId() == operationId));

    recorder.events.clear();
    Page<Pet> otherValues =
        executor.selectFrom(pet)
            .where(pet.id().in(List.of(2L, 3L, 4L)))
            .orderBy(pet.id().asc())
            .fetchPage(PageRequest.page(0, 1));
    assertEquals(2L, otherValues.totalElements());
    assertEquals(2L, otherValues.items().getFirst().id());
    assertTrue(recorder.events(CompilationCompletedEvent.class).isEmpty());

    recorder.events.clear();
    assertTrue(query.fetchSlice(SliceRequest.offset(0, 2)).hasNext());
    assertEquals(3L, recorder.events(StatementCompletedEvent.class).getFirst().readRowCount());

    recorder.events.clear();
    assertEquals(1, executor.selectFrom(pet).where(pet.name().eq("sensitive-pet-1")).fetchList().size());
    assertFalse(recorder.events.toString().contains("sensitive-pet-1"));
  }

  @Test
  void sessionsInheritTheRegistrationSnapshotAndStreamsFinishOnlyWhenClosedOrExhausted()
      throws Exception {
    JdbcDataSource dataSource = dataSource();
    List<String> order = new CopyOnWriteArrayList<>();
    Recorder first = new Recorder("first", order);
    Recorder second = new Recorder("second", order);
    Recorder late = new Recorder("late", order);
    Skis.Builder builder =
        Skis.builder(dataSource).dialect(H2Dialect.INSTANCE).listener(first).listener(second);
    SkisExecutor executor = builder.build();
    builder.listener(late);
    seed(executor);
    first.events.clear();
    second.events.clear();
    order.clear();

    executor.inTransaction(
        session -> {
          assertEquals("sensitive-pet-1", session.findById(PetTable.petTable, 1L).orElseThrow().name());
          return null;
        });
    assertEquals(1, first.events(OperationCompletedEvent.class).size());
    assertEquals(first.events, second.events);
    assertTrue(late.events.isEmpty());
    for (int index = 0; index < order.size(); index += 2) {
      assertEquals(order.get(index).replace("first:", "second:"), order.get(index + 1));
    }

    first.events.clear();
    SelectQuery<Pet> query =
        executor.selectFrom(PetTable.petTable).orderBy(PetTable.petTable.id().asc());
    try (Stream<Pet> rows = query.stream()) {
      assertEquals(1L, rows.findFirst().orElseThrow().id());
      assertTrue(first.events(OperationCompletedEvent.class).isEmpty());
    }
    assertEquals(1, first.events(OperationCompletedEvent.class).size());
    assertEquals(
        ExecutionTermination.CLOSED_EARLY,
        first.events(OperationCompletedEvent.class).getFirst().termination());
    assertEquals(1L, first.events(StatementCompletedEvent.class).getFirst().readRowCount());

    first.events.clear();
    try (Stream<Pet> rows = query.stream()) {
      assertEquals(3, rows.toList().size());
      assertEquals(1, first.events(OperationCompletedEvent.class).size());
      assertEquals(1, first.events(StatementCompletedEvent.class).size());
      assertEquals(
          ExecutionTermination.EXHAUSTED,
          first.events(OperationCompletedEvent.class).getFirst().termination());
      assertEquals(
          ExecutionTermination.EXHAUSTED,
          first.events(StatementCompletedEvent.class).getFirst().termination());
    }
    assertEquals(1, first.events(OperationCompletedEvent.class).size());
    assertEquals(1, first.events(StatementCompletedEvent.class).size());
    assertEquals(
        ExecutionTermination.EXHAUSTED,
        first.events(OperationCompletedEvent.class).getFirst().termination());
    assertEquals(3L, first.events(StatementCompletedEvent.class).getFirst().readRowCount());
  }

  @Test
  void rejectsReentryThroughAnUnobservedExecutorAndIsolatesTheListenerFailure() throws Exception {
    JdbcDataSource dataSource = dataSource();
    SkisExecutor unobserved = Skis.create(dataSource, H2Dialect.INSTANCE);
    seed(unobserved);
    AtomicInteger rejected = new AtomicInteger();
    Recorder recorder = new Recorder("second", new CopyOnWriteArrayList<>());
    ExecutionListener failing =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            assertThrows(
                IllegalStateException.class,
                () -> unobserved.selectFrom(PetTable.petTable).fetchList());
            rejected.incrementAndGet();
            throw new IllegalStateException("sensitive-listener-message");
          }
        };
    SkisExecutor observed =
        Skis.builder(dataSource).dialect(H2Dialect.INSTANCE)
            .listener(failing).listener(recorder).build();
    recorder.events.clear();

    assertEquals(3, observed.selectFrom(PetTable.petTable).fetchList().size());
    assertEquals(1, rejected.get());
    OperationCompletedEvent completed = recorder.events(OperationCompletedEvent.class).getFirst();
    assertEquals(ExecutionOperationKind.FETCH_LIST, completed.operationKind());
    assertEquals(ExecutionOutcome.SUCCEEDED, completed.outcome());
    assertFalse(recorder.events.toString().contains("sensitive-listener-message"));
    assertEquals(3, unobserved.selectFrom(PetTable.petTable).fetchList().size());
  }

  private static JdbcDataSource dataSource() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:listener_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE "pet" (
            "id" BIGINT PRIMARY KEY,
            "pet_name" VARCHAR(200) NOT NULL,
            "version" BIGINT NOT NULL
          )
          """);
    }
    return dataSource;
  }

  private static void seed(SkisExecutor executor) {
    for (long id = 1L; id <= 3L; id++) {
      executor.insertInto(PetTable.petTable).value(new Pet(id, "sensitive-pet-" + id, null)).write();
    }
  }

  private static final class Recorder implements ExecutionListener {

    private final String label;
    private final List<String> order;
    private final List<Object> events = new CopyOnWriteArrayList<>();

    private Recorder(String label, List<String> order) {
      this.label = label;
      this.order = order;
    }

    private <T> List<T> events(Class<T> type) {
      return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private void record(Object event) {
      events.add(event);
      order.add(label + ":" + event.getClass().getSimpleName());
    }

    @Override
    public void compilationCompleted(CompilationCompletedEvent event) {
      record(event);
    }

    @Override
    public void operationStarted(OperationStartedEvent event) {
      record(event);
    }

    @Override
    public void operationCompleted(OperationCompletedEvent event) {
      record(event);
    }

    @Override
    public void statementStarted(StatementStartedEvent event) {
      record(event);
    }

    @Override
    public void statementCompleted(StatementCompletedEvent event) {
      record(event);
    }
  }
}
