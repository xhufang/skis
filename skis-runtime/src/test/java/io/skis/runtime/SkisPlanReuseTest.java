package io.skis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.SkisConfigurationException;
import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisErrorCode;
import io.skis.core.spi.CompilationCompletedEvent;
import io.skis.core.spi.CompilationOrigin;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionOutcome;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.OperationCompletedEvent;
import io.skis.core.spi.OperationStartedEvent;
import io.skis.core.spi.StatementCompletedEvent;
import io.skis.core.spi.StatementStartedEvent;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.mapping.spi.EntityMutationBinders;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.QueryPlanCacheStatistics;
import io.skis.query.QueryCursor;
import io.skis.query.QueryTable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SkisPlanReuseTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("pet_name", false));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "shelter", "pet"),
          List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);
  private static final PetTable PET_TABLE = new PetTable();

  @Test
  void openingTransactionsOnlyRebindsThePrecompiledPlanCatalogs() {
    CountingDialect dialect = new CountingDialect();
    SkisExecutor executor =
        Skis.builder(dataSource()).dialect(dialect).runtimeModels(List.of(runtimeModel())).build();
    int rendersAfterAssembly = dialect.renders.get();

    try (SkisSession ignored = executor.beginTransaction()) {
      assertEquals(rendersAfterAssembly, dialect.renders.get());
    }
    try (SkisSession ignored = executor.beginTransaction()) {
      assertEquals(rendersAfterAssembly, dialect.renders.get());
    }

    assertTrue(rendersAfterAssembly > 0);
  }

  @Test
  void supportsDisabledDynamicPlanCacheAndRejectsInvalidConfiguration() {
    SkisExecutor disabled =
        Skis.builder(dataSource())
            .dialect(new CountingDialect())
            .runtimeModels(List.of(runtimeModel()))
            .planCacheMaximumSize(0)
            .build();

    assertEquals(
        new QueryPlanCacheStatistics(0, 0, 0, 0, 0, 0), disabled.queryPlanCacheStatistics());
    disabled.clearQueryPlanCache();
    assertEquals(
        new QueryPlanCacheStatistics(0, 0, 0, 0, 0, 0), disabled.queryPlanCacheStatistics());
    assertThrows(
        SkisConfigurationException.class,
        () -> Skis.builder(dataSource()).planCacheMaximumSize(-1));
    assertThrows(
        SkisConfigurationException.class,
        () -> Skis.builder(dataSource()).planCacheExpireAfterAccess(Duration.ZERO));
  }

  @Test
  void requiresADataSourceAndExplicitDialect() {
    assertThrows(NullPointerException.class, () -> Skis.builder(null));
    SkisConfigurationException missingDialect =
        assertThrows(SkisConfigurationException.class, () -> Skis.builder(dataSource()).build());
    assertEquals(SkisErrorCode.ASSEMBLY_CONFIGURATION_INVALID, missingDialect.code());
    assertThrows(NullPointerException.class, () -> Skis.create(dataSource(), null));
  }

  @Test
  void publishesPrewarmMaterializedAndCursorLifecyclesWithStableCorrelation() {
    List<Object> events = new ArrayList<>();
    ExecutionListener listener = recordingListener(events);
    SkisExecutor executor =
        Skis.builder(executionDataSource())
            .dialect(new CountingDialect())
            .runtimeModels(List.of(runtimeModel()))
            .listener(listener)
            .build();

    List<CompilationCompletedEvent> prewarm =
        events.stream()
            .filter(CompilationCompletedEvent.class::isInstance)
            .map(CompilationCompletedEvent.class::cast)
            .toList();
    assertTrue(prewarm.size() >= 4);
    assertTrue(
        prewarm.stream()
            .allMatch(
                event ->
                    event.origin() == CompilationOrigin.PREWARM
                        && event.operationId() == CompilationCompletedEvent.NO_OPERATION_ID
                        && event.outcome() == ExecutionOutcome.SUCCEEDED));
    assertTrue(events.stream().noneMatch(OperationStartedEvent.class::isInstance));

    events.clear();
    assertTrue(executor.findById(PET_TABLE, 7L).isPresent());
    assertEquals(4, events.size());
    OperationStartedEvent operationStarted =
        assertInstanceOf(OperationStartedEvent.class, events.get(0));
    StatementStartedEvent statementStarted =
        assertInstanceOf(StatementStartedEvent.class, events.get(1));
    StatementCompletedEvent statementCompleted =
        assertInstanceOf(StatementCompletedEvent.class, events.get(2));
    OperationCompletedEvent operationCompleted =
        assertInstanceOf(OperationCompletedEvent.class, events.get(3));
    assertEquals(ExecutionOperationKind.FIND_BY_ID, operationStarted.operationKind());
    assertEquals(operationStarted.operationId(), statementStarted.operationId());
    assertEquals(statementStarted.statementId(), statementCompleted.statementId());
    assertEquals(operationStarted.operationId(), operationCompleted.operationId());
    assertEquals(1L, statementCompleted.readRowCount());
    assertEquals(StatementCompletedEvent.UNKNOWN_ROW_COUNT, statementCompleted.affectedRowCount());
    assertEquals(ExecutionTermination.COMPLETED, operationCompleted.termination());

    events.clear();
    assertEquals(1, executor.insertInto(PET_TABLE).value(new Pet(2L, "Fifi")).write());
    OperationStartedEvent mutationStarted =
        assertInstanceOf(OperationStartedEvent.class, events.get(0));
    StatementCompletedEvent mutationStatement =
        assertInstanceOf(StatementCompletedEvent.class, events.get(2));
    assertEquals(ExecutionOperationKind.INSERT, mutationStarted.operationKind());
    assertEquals(StatementCompletedEvent.UNKNOWN_ROW_COUNT, mutationStatement.readRowCount());
    assertEquals(1L, mutationStatement.affectedRowCount());

    events.clear();
    QueryCursor<Pet> cursor = executor.selectFrom(PET_TABLE).cursor();
    assertTrue(events.stream().anyMatch(CompilationCompletedEvent.class::isInstance));
    assertTrue(events.stream().anyMatch(OperationStartedEvent.class::isInstance));
    assertTrue(events.stream().anyMatch(StatementStartedEvent.class::isInstance));
    assertTrue(events.stream().noneMatch(StatementCompletedEvent.class::isInstance));
    assertTrue(events.stream().noneMatch(OperationCompletedEvent.class::isInstance));
    assertTrue(cursor.advance());
    assertEquals(new Pet(1L, "Mimi"), cursor.current());
    assertFalse(cursor.advance());
    int eventCountAfterExhaustion = events.size();
    cursor.close();
    assertEquals(eventCountAfterExhaustion, events.size());
    StatementCompletedEvent cursorStatement =
        events.stream()
            .filter(StatementCompletedEvent.class::isInstance)
            .map(StatementCompletedEvent.class::cast)
            .findFirst()
            .orElseThrow();
    OperationCompletedEvent cursorOperation =
        events.stream()
            .filter(OperationCompletedEvent.class::isInstance)
            .map(OperationCompletedEvent.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(ExecutionTermination.EXHAUSTED, cursorStatement.termination());
    assertEquals(ExecutionTermination.EXHAUSTED, cursorOperation.termination());
    assertEquals(1L, cursorStatement.readRowCount());

    events.clear();
    QueryCursor<Pet> closedEarly = executor.selectFrom(PET_TABLE).cursor();
    assertTrue(events.stream().noneMatch(CompilationCompletedEvent.class::isInstance));
    closedEarly.close();
    int eventCountAfterClose = events.size();
    closedEarly.close();
    assertEquals(eventCountAfterClose, events.size());
    StatementCompletedEvent closedEarlyStatement =
        events.stream()
            .filter(StatementCompletedEvent.class::isInstance)
            .map(StatementCompletedEvent.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(ExecutionTermination.CLOSED_EARLY, closedEarlyStatement.termination());
    assertEquals(0L, closedEarlyStatement.readRowCount());

    events.clear();
    try (var stream = executor.selectFrom(PET_TABLE).stream()) {
      assertEquals(new Pet(1L, "Mimi"), stream.findFirst().orElseThrow());
    }
    OperationStartedEvent streamStarted =
        events.stream()
            .filter(OperationStartedEvent.class::isInstance)
            .map(OperationStartedEvent.class::cast)
            .findFirst()
            .orElseThrow();
    OperationCompletedEvent streamCompleted =
        events.stream()
            .filter(OperationCompletedEvent.class::isInstance)
            .map(OperationCompletedEvent.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(ExecutionOperationKind.STREAM, streamStarted.operationKind());
    assertEquals(ExecutionTermination.CLOSED_EARLY, streamCompleted.termination());
  }

  @Test
  void isolatesListenerExceptionsRejectsReentryAndContinuesInRegistrationOrder() {
    AtomicReference<SkisExecutor> owner = new AtomicReference<>();
    AtomicReference<IllegalStateException> rejection = new AtomicReference<>();
    List<String> callbacks = new ArrayList<>();
    ExecutionListener reentering =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            callbacks.add("first-start");
            try {
              owner.get().findById(PET_TABLE, 8L);
            } catch (IllegalStateException failure) {
              rejection.set(failure);
            }
            throw new IllegalStateException("isolated listener failure");
          }
        };
    ExecutionListener follower =
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
    SkisExecutor executor =
        Skis.builder(executionDataSource())
            .dialect(new CountingDialect())
            .runtimeModels(List.of(runtimeModel()))
            .listener(reentering)
            .listener(follower)
            .build();
    owner.set(executor);

    assertTrue(executor.findById(PET_TABLE, 8L).isPresent());
    assertEquals(
        "SKIS compilation or execution must not be entered from an execution listener callback",
        rejection.get().getMessage());
    assertEquals(List.of("first-start", "second-start", "second-complete"), callbacks);
  }

  @Test
  void propagatesListenerErrorsBeforeConnectionAcquisition() {
    AtomicInteger acquisitions = new AtomicInteger();
    AssertionError expected = new AssertionError("listener error");
    SkisExecutor executor =
        Skis.builder(executionDataSource(acquisitions))
            .dialect(new CountingDialect())
            .runtimeModels(List.of(runtimeModel()))
            .listener(
                new ExecutionListener() {
                  @Override
                  public void operationStarted(OperationStartedEvent event) {
                    throw expected;
                  }
                })
            .build();

    AssertionError actual =
        assertThrows(AssertionError.class, () -> executor.findById(PET_TABLE, 9L));
    assertEquals(expected, actual);
    assertEquals(0, acquisitions.get());
  }

  @Test
  void capturesBuilderSnapshotsPreservesDuplicatesAndSharesListenersWithSessions() {
    AtomicInteger firstStarts = new AtomicInteger();
    AtomicInteger secondStarts = new AtomicInteger();
    ExecutionListener first =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            firstStarts.incrementAndGet();
          }
        };
    ExecutionListener second =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            secondStarts.incrementAndGet();
          }
        };
    Skis.Builder builder =
        Skis.builder(executionDataSource())
            .dialect(new CountingDialect())
            .runtimeModels(List.of(runtimeModel()))
            .listener(first);
    SkisExecutor firstExecutor = builder.build();
    SkisExecutor secondExecutor = builder.listener(first).listener(second).build();

    assertTrue(firstExecutor.findById(PET_TABLE, 10L).isPresent());
    assertEquals(1, firstStarts.get());
    assertEquals(0, secondStarts.get());

    assertTrue(secondExecutor.findById(PET_TABLE, 11L).isPresent());
    assertEquals(3, firstStarts.get());
    assertEquals(1, secondStarts.get());

    try (SkisSession session = secondExecutor.beginTransaction()) {
      assertTrue(session.findById(PET_TABLE, 12L).isPresent());
      session.rollback();
    }
    assertEquals(5, firstStarts.get());
    assertEquals(2, secondStarts.get());
  }

  @Test
  void reportsPrewarmCompilationFailureWithoutInventingAnOperation() {
    IllegalStateException expected = new IllegalStateException("render failed");
    List<CompilationCompletedEvent> events = new ArrayList<>();
    Dialect failingDialect =
        new Dialect() {
          @Override
          public String id() {
            return "failing";
          }

          @Override
          public IdentifierRules identifierRules() {
            return StandardIdentifierRules.INSTANCE;
          }

          @Override
          public DialectCapabilities capabilities() {
            return DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
          }

          @Override
          public SqlRenderer renderer() {
            return input -> {
              throw expected;
            };
          }
        };
    ExecutionListener listener =
        new ExecutionListener() {
          @Override
          public void compilationCompleted(CompilationCompletedEvent event) {
            events.add(event);
          }
        };

    assertThrows(
        IllegalStateException.class,
        () ->
            Skis.builder(executionDataSource())
                .dialect(failingDialect)
                .runtimeModels(List.of(runtimeModel()))
                .listener(listener)
                .build());

    assertEquals(1, events.size());
    CompilationCompletedEvent event = events.getFirst();
    assertEquals(CompilationOrigin.PREWARM, event.origin());
    assertEquals(CompilationCompletedEvent.NO_OPERATION_ID, event.operationId());
    assertEquals(ExecutionOutcome.FAILED, event.outcome());
    assertEquals(SkisDiagnosticStage.COMPILATION, event.failureStage());
    assertNull(event.sqlFingerprint());
    assertNull(event.sqlTemplate());
  }

  private static ExecutionListener recordingListener(List<Object> events) {
    return new ExecutionListener() {
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
    };
  }

  private static EntityRuntimeModel<Pet> runtimeModel() {
    EntityMutationBinders<Pet> binders =
        new EntityMutationBinders<>(
            (statement, firstIndex, value, context) -> firstIndex + 2,
            (statement, firstIndex, value, context) -> firstIndex + 2,
            (statement, firstIndex, value, context) -> firstIndex + 2,
            null,
            (statement, firstIndex, arguments, context) -> firstIndex + 2,
            new io.skis.mapping.spi.EntityIdAccess<Pet>() {
              @Override public Object read(Pet entity) { return entity.id(); }
              @Override public Pet apply(Pet entity, Object id) {
                return new Pet((Long) id, entity.name());
              }
            });
    return new EntityRuntimeModel<>(
        PET,
        layout -> (resultSet, context) -> new Pet(1L, "Mimi"),
        List.of(
            new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(NAME, JdbcCodecs.STRING)),
        binders);
  }

  private static DataSource dataSource() {
    Connection connection =
        proxy(
            Connection.class,
            (ignored, method, arguments) ->
                method.getName().equals("getAutoCommit")
                    ? true
                    : defaultValue(method.getReturnType()));
    return proxy(
        DataSource.class,
        (ignored, method, arguments) ->
            method.getName().equals("getConnection")
                ? connection
                : defaultValue(method.getReturnType()));
  }

  private static DataSource executionDataSource() {
    return executionDataSource(new AtomicInteger());
  }

  private static DataSource executionDataSource(AtomicInteger acquisitions) {
    Connection connection =
        proxy(
            Connection.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("prepareStatement")) {
                return preparedStatement();
              }
              if (method.getName().equals("getAutoCommit")) {
                return true;
              }
              return defaultValue(method.getReturnType());
            });
    return proxy(
        DataSource.class,
        (ignored, method, arguments) -> {
          if (method.getName().equals("getConnection")) {
            acquisitions.incrementAndGet();
            return connection;
          }
          return defaultValue(method.getReturnType());
        });
  }

  private static PreparedStatement preparedStatement() {
    return proxy(
        PreparedStatement.class,
        (ignored, method, arguments) -> {
          if (method.getName().equals("executeQuery")) {
            return oneRowResultSet();
          }
          if (method.getName().equals("executeUpdate")) {
            return 1;
          }
          return defaultValue(method.getReturnType());
        });
  }

  private static ResultSet oneRowResultSet() {
    AtomicInteger position = new AtomicInteger();
    return proxy(
        ResultSet.class,
        (ignored, method, arguments) ->
            method.getName().equals("next")
                ? position.getAndIncrement() == 0
                : defaultValue(method.getReturnType()));
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive() || type == void.class) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    throw new AssertionError(type);
  }

  private record Pet(Long id, String name) {}

  private static final class PetTable extends QueryTable<Pet> {

    private PetTable() {
      super(PET);
    }

    private PetTable(String alias) {
      super(PET, alias);
    }

    @Override
    public PetTable as(String alias) {
      return new PetTable(alias);
    }
  }

  private static final class CountingDialect implements Dialect {

    private final AtomicInteger renders = new AtomicInteger();
    private final DialectCapabilities capabilities =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    private final SqlRenderer delegate = new StandardSqlRenderer(this);

    @Override
    public String id() {
      return "counting";
    }

    @Override
    public IdentifierRules identifierRules() {
      return StandardIdentifierRules.INSTANCE;
    }

    @Override
    public DialectCapabilities capabilities() {
      return capabilities;
    }

    @Override
    public SqlRenderer renderer() {
      return input -> {
        renders.incrementAndGet();
        return delegate.render(input);
      };
    }
  }
}
