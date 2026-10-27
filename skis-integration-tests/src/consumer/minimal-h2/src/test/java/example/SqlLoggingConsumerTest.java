package example;

import static org.junit.jupiter.api.Assertions.*;

import example.skis.LoggingPetTable;
import io.skis.core.SqlLoggingOptions;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.StatementCompletedEvent;
import io.skis.dialect.h2.H2Dialect;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.query.PageRequest;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import io.skis.testlogging.TestLoggerProvider;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqlLoggingConsumerTest {
  private static final String PARAMETERS = "io.skis.sql.parameters";
  @BeforeEach void reset() { TestLoggerProvider.reset(); LoggingPet.resetReads(); }
  @AfterEach void clear() { TestLoggerProvider.reset(); }

  @Test void generatedMutationBindersReadOnceAndSessionsInheritTheExplicitPolicy() throws Exception {
    TestLoggerProvider.DEBUG.add(PARAMETERS);
    List<String> slots = new ArrayList<>();
    SkisExecutor executor = executor(SqlLoggingOptions.builder().parameters(true)
        .parameterRedactor((index, type, value) -> { slots.add(index + ":" + type.getSimpleName()); return "approved"; })
        .build(), new ArrayList<>());
    var table = LoggingPetTable.loggingPetTable;
    executor.insert(table, new LoggingPet(1L, "secret-parameter", null));
    // ASSIGNED validates its key once before acquisition; the generated binder reads each slot once.
    assertEquals(List.of(2, 1, 1), LoggingPet.reads());
    assertEquals(List.of("1:Long", "2:String", "3:Long"), slots);
    assertTrue(parameterMessages().getFirst().contains("1=approved, 2=approved, 3=approved"));
    slots.clear();
    TestLoggerProvider.EVENTS.clear();
    LoggingPet.resetReads();
    executor.inTransaction(session -> {
      assertEquals(1, session.updateById(table, new LoggingPet(1L, "secret-updated", 0L)));
      assertTrue(session.findById(table, 1L).isPresent());
      return null;
    });
    // The mutation facade already reads the optimistic version once before binding.
    assertEquals(List.of(1, 1, 2), LoggingPet.reads());
    assertEquals(List.of("1:String", "2:Long", "3:Long", "1:Long"), slots);
    assertEquals(2, parameterMessages().size());
    executor.deleteById(table, 1L);
    assertEquals("1:Long", slots.getLast());
    assertFalse(TestLoggerProvider.EVENTS.toString().contains("secret-"));
  }

  @Test void pageHasIndependentFinalSlotSummariesIncludingPaginationAndNoValuesInEvents() throws Exception {
    TestLoggerProvider.DEBUG.add(PARAMETERS);
    List<StatementCompletedEvent> events = new ArrayList<>();
    SkisExecutor executor = executor(SqlLoggingOptions.builder().parameters(true).build(), events);
    var table = LoggingPetTable.loggingPetTable;
    for (long id = 1; id <= 3; id++) executor.insert(table, new LoggingPet(id, "secret-result", null));
    TestLoggerProvider.EVENTS.clear();
    events.clear();
    var page = executor.selectFrom(table).where(table.id().ge(1L)).orderBy(table.id().asc())
        .fetchPage(PageRequest.page(1, 1));
    assertEquals(3, page.totalElements());
    List<String> summaries = parameterMessages();
    assertEquals(2, summaries.size());
    assertTrue(summaries.get(0).contains("1=java.lang.Long, 2=java.lang.Integer, 3=java.lang.Long"));
    assertTrue(summaries.get(1).contains("parameters=[1=java.lang.Long]"));
    assertEquals(2, events.size());
    assertEquals(events.getFirst().operationId(), events.getLast().operationId());
    assertNotEquals(events.getFirst().statementId(), events.getLast().statementId());
    for (int index = 0; index < events.size(); index++) {
      assertTrue(summaries.get(index).contains(
          ", operation=" + events.get(index).operationId()
              + ", statement=" + events.get(index).statementId() + ","));
    }
    assertFalse(events.toString().contains("secret-result"));
    assertFalse(events.toString().contains("parameters="));
    assertFalse(TestLoggerProvider.EVENTS.toString().contains("secret-result"));
  }

  @Test void driverSensitiveFailureKeepsCauseWhileDefaultLogsContainOnlySafeMetadata() throws Exception {
    TestLoggerProvider.DEBUG.add(PARAMETERS);
    TestLoggerProvider.DEBUG.add("io.skis.sql");
    TestLoggerProvider.WARN.add("io.skis.sql.slow");
    SkisExecutor executor = executor(SqlLoggingOptions.builder().sql(true).parameters(true)
        .slowQueryThreshold(Duration.ofNanos(1)).build(), new ArrayList<>());
    var table = LoggingPetTable.loggingPetTable;
    executor.insert(table, new LoggingPet(1L, "secret-driver-row", null));
    RuntimeException failure = assertThrows(RuntimeException.class,
        () -> executor.insert(table, new LoggingPet(1L, "secret-driver-value", null)));
    assertNotNull(failure.getCause());
    assertTrue(failure.getCause() instanceof java.sql.SQLException);
    assertFalse(TestLoggerProvider.EVENTS.toString().contains("secret-driver"));
    assertFalse(TestLoggerProvider.EVENTS.toString().contains("JdbcSQLIntegrityConstraintViolationException"));
  }

  @Test void synchronousLogBackendsCannotReenterAnotherExecutorBeforeConnectionAccess()
      throws Exception {
    TestLoggerProvider.DEBUG.add("io.skis.sql");
    TestLoggerProvider.DEBUG.add(PARAMETERS);
    TestLoggerProvider.WARN.add("io.skis.sql.slow");
    AtomicInteger acquisitions = new AtomicInteger();
    SkisExecutor target = Skis.builder(new JdbcDataSource()).connectionProvider(new ConnectionProvider() {
      @Override public Connection acquire(ExecutionContext context) {
        acquisitions.incrementAndGet();
        throw new AssertionError("logging re-entry reached connection acquisition");
      }
      @Override public void release(Connection connection, ExecutionContext context) {
        throw new AssertionError("logging re-entry acquired a connection");
      }
    }).dialect(H2Dialect.INSTANCE).build();
    var table = LoggingPetTable.loggingPetTable;
    for (boolean probe : List.of(false, true)) {
      for (var options : List.of(SqlLoggingOptions.builder().sql(true).build(),
          SqlLoggingOptions.builder().parameters(true).build(),
          SqlLoggingOptions.builder().slowQueryThreshold(Duration.ofNanos(1)).build())) {
        SkisExecutor outer = executor(options, new ArrayList<>());
        AtomicInteger rejected = new AtomicInteger();
        Runnable callback = () -> {
          assertThrows(IllegalStateException.class, () -> target.findById(table, 1L));
          rejected.incrementAndGet();
        };
        if (probe) TestLoggerProvider.onProbe = (logger, level) -> callback.run();
        else TestLoggerProvider.onLog = callback;
        assertEquals(1, outer.insert(table, new LoggingPet(1L, "secret-outer", null)));
        assertTrue(rejected.get() > 0);
        assertEquals(0, acquisitions.get());
        TestLoggerProvider.onProbe = null;
        TestLoggerProvider.onLog = null;
        assertTrue(outer.findById(table, 1L).isPresent());
      }
    }
  }

  @Test void queryAndMutationCaptureLoggingDemandOnceWithoutUserListeners() throws Exception {
    TestLoggerProvider.DEBUG.add("io.skis.sql");
    var options = SqlLoggingOptions.builder().sql(true).slowQueryThreshold(Duration.ofHours(1)).build();
    SkisExecutor executor = Skis.builder(dataSource()).dialect(H2Dialect.INSTANCE).sqlLogging(options).build();
    var table = LoggingPetTable.loggingPetTable;
    executor.insert(table, new LoggingPet(1L, "secret-warmup", null));
    assertTrue(executor.findById(table, 1L).isPresent());
    AtomicInteger sqlProbes = new AtomicInteger();
    AtomicInteger slowProbes = new AtomicInteger();
    TestLoggerProvider.onProbe = (logger, level) -> {
      if (logger.equals("io.skis.sql")) sqlProbes.incrementAndGet();
      else if (logger.equals("io.skis.sql.slow")) slowProbes.incrementAndGet();
      else fail("unexpected logger probe: " + logger);
    };
    assertTrue(executor.findById(table, 1L).isPresent());
    assertEquals(1, executor.insert(table, new LoggingPet(2L, "secret-second", null)));
    // The backend keeps its string-only output checks separate from framework demand probes.
    assertEquals(2, sqlProbes.get());
    assertEquals(2, slowProbes.get());
  }

  private static List<String> parameterMessages() {
    return TestLoggerProvider.EVENTS.stream().filter(event -> event.logger().equals(PARAMETERS))
        .map(TestLoggerProvider.Entry::message).toList();
  }

  private static SkisExecutor executor(SqlLoggingOptions options, List<StatementCompletedEvent> events)
      throws Exception {
    return Skis.builder(dataSource()).dialect(H2Dialect.INSTANCE).sqlLogging(options)
        .listener(new ExecutionListener() {
          @Override public void statementCompleted(StatementCompletedEvent event) { events.add(event); }
        }).build();
  }

  private static JdbcDataSource dataSource() throws Exception {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:logging_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE \"logging_pet\" (\"id\" BIGINT PRIMARY KEY, "
          + "\"secret\" VARCHAR(200) NOT NULL, \"version\" BIGINT NOT NULL)");
    }
    return source;
  }
}
