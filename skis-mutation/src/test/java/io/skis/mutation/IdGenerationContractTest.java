package io.skis.mutation;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.*;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.ExecutionOutcome;
import io.skis.core.spi.OperationCompletedEvent;
import io.skis.core.spi.StatementStartedEvent;
import io.skis.dialect.spi.*;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.*;
import io.skis.metadata.*;
import io.skis.mutation.internal.MutationRuntime;
import io.skis.query.QueryTable;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class IdGenerationContractTest {
  @Test
  void acceptsPrimitiveMetadataForAssignedInsertsAndDeletes() {
    assertAssignedPrimitiveId(long.class, JdbcCodecs.LONG, 0L);
    assertAssignedPrimitiveId(int.class, JdbcCodecs.INTEGER, 0);
  }

  private static <I> void assertAssignedPrimitiveId(Class<I> type, JdbcTypeCodec<I> codec, I id) {
    var fixture = new Fixture<>(type, codec, IdGeneration.ASSIGNED);
    var operations = fixture.operations();
    var bean = new Bean(id);
    assertEquals(1, operations.insert(fixture.table, bean));
    assertSame(bean, operations.insertResult(fixture.table, bean).entity());
    assertEquals(1, operations.deleteById(fixture.table, id));
    assertEquals(List.of(id, id, id), fixture.bound);
    assertEquals(0, bean.writes);
    assertEquals(SkisErrorCode.MUTATION_REQUIRED_VALUE_NULL,
        assertThrows(MutationException.class,
            () -> operations.insert(fixture.table, new Bean(null))).code());
    assertEquals(SkisErrorCode.MUTATION_VALUE_TYPE_MISMATCH,
        assertThrows(MutationException.class,
            () -> operations.insert(fixture.table, new Bean("wrong Java type"))).code());
    assertEquals(SkisErrorCode.MUTATION_VALUE_TYPE_MISMATCH,
        assertThrows(MutationException.class,
            () -> operations.deleteById(fixture.table, "wrong Java type")).code());
    assertEquals(3, Collections.frequency(fixture.events, "acquire"));
  }

  @Test
  void preservesAssignedZeroAndExistingGeneratedValuesWithoutApplyingThemAgain() {
    for (IdGeneration generation : List.of(IdGeneration.ASSIGNED, IdGeneration.SNOWFLAKE)) {
      var fixture = new Fixture<>(Long.class, JdbcCodecs.LONG, generation);
      var bean = new Bean(generation == IdGeneration.ASSIGNED ? 0L : 7L);
      assertSame(bean, fixture.operations().insertResult(fixture.table, bean).entity());
      assertEquals(bean.id, fixture.bound.getFirst());
      assertEquals(1, bean.reads);
      assertEquals(0, bean.writes);
    }
    var text = new Fixture<>(String.class, JdbcCodecs.STRING, IdGeneration.UUID);
    var existing = new Bean("00000000-0000-1000-8000-000000000007");
    assertEquals(1, text.operations().insert(text.table, existing));
    assertEquals(0, existing.writes);
  }

  @Test
  void generatesOnceAndAppliesOnlyAfterRelease() {
    var fixture = new Fixture<>(UUID.class, JdbcCodecs.UUID, IdGeneration.UUID);
    var bean = new Bean(null);
    fixture.operations().insert(fixture.table, bean);
    UUID key = assertInstanceOf(UUID.class, bean.id);
    assertEquals(4, key.version());
    assertEquals(2, key.variant());
    assertSame(bean.id, fixture.bound.getFirst());
    assertEquals(List.of("acquire", "bind", "execute", "close", "release", "apply"), fixture.events);
    assertEquals(1, bean.reads);
    assertEquals(1, bean.writes);
    assertFalse(new InsertArguments<>(bean, key).toString().contains(key.toString()));
    assertFalse(new InsertResult<>(1, bean).toString().contains(key.toString()));
  }

  @Test
  void failedExecutionOrCleanupDoesNotApplyTheKey() {
    for (String phase : List.of("acquire", "bind", "execute", "close", "release")) {
      var fixture = new Fixture<>(UUID.class, JdbcCodecs.UUID, IdGeneration.UUID);
      fixture.failurePoint = phase;
      var bean = new Bean(null);
      assertThrows(RuntimeException.class, () -> fixture.operations().insert(fixture.table, bean));
      assertNull(bean.id);
      assertEquals(0, bean.writes);
      assertFalse(fixture.events.contains("apply"));
      assertTrue(Collections.frequency(fixture.events, "execute") <= 1);
    }
  }

  @Test
  void validatesInputsAndSnowflakeConfigurationBeforeAnyConnection() {
    var assigned = new Fixture<>(Long.class, JdbcCodecs.LONG, IdGeneration.ASSIGNED);
    assertEquals(SkisErrorCode.MUTATION_REQUIRED_VALUE_NULL,
        assertThrows(MutationException.class,
            () -> assigned.operations().insert(assigned.table, new Bean(null))).code());
    assertTrue(assigned.events.isEmpty());
    assertEquals(SkisErrorCode.MUTATION_VALUE_TYPE_MISMATCH,
        assertThrows(MutationException.class,
            () -> assigned.operations().insert(assigned.table, new Bean("wrong Java type"))).code());
    var snow = new Fixture<>(Long.class, JdbcCodecs.LONG, IdGeneration.SNOWFLAKE);
    assertThrows(SkisConfigurationException.class, () -> MutationRuntime.compile(
        EntityRuntimeRegistry.of(List.of(snow.model)), TestDialect.INSTANCE));
    assertThrows(MutationException.class, () -> snow.operations().insert(snow.table, new Bean(0L)));
    assertThrows(MutationException.class, () -> snow.operations().insert(snow.table, new Bean(-1L)));
    var uuid = new Fixture<>(String.class, JdbcCodecs.STRING, IdGeneration.UUID);
    for (String text : List.of("", " ", "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF", "1-1-1-1-1")) {
      assertThrows(MutationException.class, () -> uuid.operations().insert(uuid.table, new Bean(text)));
    }
    assertTrue(uuid.events.isEmpty());
  }

  @Test
  void preservesSnowflakeFailureReasonsAndContextInExceptionsAndOperationEvents() throws Exception {
    for (boolean resultRequested : List.of(false, true)) {
      var fixture = new Fixture<>(Long.class, JdbcCodecs.LONG, IdGeneration.SNOWFLAKE);
      var generator = SnowflakeIdGenerator.ofNode(17);
      var stateField = SnowflakeIdGenerator.class.getDeclaredField("state");
      stateField.setAccessible(true);
      var state = (AtomicLong) stateField.get(generator);
      // A valid future high-water mark triggers rollback without depending on clock timing.
      long highWaterMark = ((1L << 41) - 1) << 12;
      state.set(highWaterMark);
      var completed = new ArrayList<OperationCompletedEvent>();
      var startedStatements = new ArrayList<StatementStartedEvent>();
      var dispatcher = ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {
        @Override public void operationCompleted(OperationCompletedEvent event) {
          completed.add(event);
        }
        @Override public void statementStarted(StatementStartedEvent event) {
          startedStatements.add(event);
        }
      }));
      var operations = fixture.operations(generator, dispatcher);
      var bean = new Bean(null);
      var failure = assertThrows(MutationException.class, () -> {
        if (resultRequested) {
          operations.insertResult(fixture.table, bean);
        } else {
          operations.insert(fixture.table, bean);
        }
      });
      assertEquals(SkisErrorCode.MUTATION_ID_GENERATION_FAILED, failure.code());
      var original = assertInstanceOf(MutationException.class, failure.getCause());
      assertEquals(failure.code(), original.code());
      String reason =
          Objects.requireNonNull(Objects.requireNonNull(original.diagnostics().facts()).detail());
      assertEquals("snowflake wall clock moved backwards", reason);
      assertEquals("entity=" + fixture.model.entity().entityName()
          + ", property=id, strategy=SNOWFLAKE: " + reason,
          Objects.requireNonNull(failure.diagnostics().facts()).detail());
      assertTrue(failure.getMessage().contains(reason));
      assertEquals(1, completed.size());
      var event = completed.getFirst();
      assertEquals(ExecutionOutcome.FAILED, event.outcome());
      assertEquals(SkisDiagnosticStage.EXECUTION, event.failureStage());
      assertSame(failure.diagnostics(), event.diagnostics());
      assertTrue(startedStatements.isEmpty());
      assertTrue(fixture.events.isEmpty());
      assertTrue(fixture.bound.isEmpty());
      assertNull(bean.id);
      assertEquals(1, bean.reads);
      assertEquals(0, bean.writes);
      assertEquals(highWaterMark, state.get());
    }
  }

  @Test
  void concurrentUuidGenerationUsesOneDatabaseAttemptPerInsert() throws Exception {
    var fixture = new Fixture<>(UUID.class, JdbcCodecs.UUID, IdGeneration.UUID);
    var operations = fixture.operations();
    var keys = ConcurrentHashMap.<UUID>newKeySet();
    var tasks = new ArrayList<Callable<Void>>();
    for (int index = 0; index < 128; index++) {
      tasks.add(() -> {
        var bean = new Bean(null);
        operations.insert(fixture.table, bean);
        assertTrue(keys.add((UUID) bean.id));
        assertEquals(1, bean.reads);
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (var future : executor.invokeAll(tasks)) { future.get(); }
    }
    assertEquals(128, keys.size());
    assertEquals(128, Collections.frequency(fixture.events, "acquire"));
    assertEquals(128, Collections.frequency(fixture.events, "execute"));
    assertEquals(128, fixture.bound.size());
  }

  private static final class Bean {
    @Nullable Object id;
    int reads;
    int writes;
    Bean(@Nullable Object id) { this.id = id; }
  }

  private static final class Fixture<I> {
    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final List<Object> bound = Collections.synchronizedList(new ArrayList<>());
    final EntityRuntimeModel<Bean> model;
    final QueryTable<Bean> table;
    @Nullable String failurePoint;

    Fixture(Class<I> type, JdbcTypeCodec<I> codec, IdGeneration generation) {
      var id = new PropertyMeta<Bean, I>(0, "id", type, ColumnMeta.of("id", false));
      var metadata = EntityMeta.simple(Bean.class, TableMeta.of("item"), List.of(id),
          new PrimaryKeyMeta<>(List.of(id), generation), false);
      EntityIdAccess<Bean> access = new EntityIdAccess<>() {
        @Override public @Nullable Object read(Bean entity) { entity.reads++; return entity.id; }
        @Override public Bean apply(Bean entity, Object key) {
          events.add("apply"); entity.writes++; entity.id = key; return entity;
        }
      };
      ParameterBinder<Bean> assigned = (statement, index, bean, context) -> {
        step("bind"); bound.add(Objects.requireNonNull(bean.id)); return index + 1;
      };
      ParameterBinder<InsertArguments<Bean>> generated = (statement, index, arguments, context) -> {
        step("bind"); bound.add(Objects.requireNonNull(arguments.effectiveId())); return index + 1;
      };
      model = new EntityRuntimeModel<>(metadata, layout -> (results, context) -> new Bean(null),
          List.of(new PropertyRuntime<>(id, codec)),
          new EntityMutationBinders<>(assigned, assigned, assigned, null, generated, access));
      table = new QueryTable<Bean>(metadata) {
        @Override public QueryTable<Bean> as(String alias) { throw new UnsupportedOperationException(); }
      };
    }

    void step(String phase) throws SQLException {
      events.add(phase);
      if (phase.equals(failurePoint)) { throw new SQLException("failure", "08006"); }
    }

    MutationOperations operations() {
      return operations(SnowflakeIdGenerator.ofNode(17), ExecutionListenerDispatcher.NONE);
    }

    MutationOperations operations(
        SnowflakeIdGenerator generator, ExecutionListenerDispatcher dispatcher) {
      return MutationRuntime.compile(EntityRuntimeRegistry.of(List.of(model)), TestDialect.INSTANCE,
          dispatcher, generator).bind(new JdbcExecutor(
          new ConnectionProvider() {
            @Override public Connection acquire(ExecutionContext context) throws SQLException {
              step("acquire");
              return proxy(Connection.class, (ignored, method, args) -> {
                if (!method.getName().equals("prepareStatement")) { throw new AssertionError(method.getName()); }
                return proxy(PreparedStatement.class, (unused, operation, values) -> switch (operation.getName()) {
                  case "setLong", "setInt" -> {
                    assertEquals(1, values[0]);
                    step("bind");
                    bound.add(values[1]);
                    yield null;
                  }
                  case "executeUpdate" -> { step("execute"); yield 1; }
                  case "close" -> { step("close"); yield null; }
                  default -> throw new AssertionError(operation.getName());
                });
              });
            }
            @Override public void release(Connection connection, ExecutionContext context) throws SQLException {
              step("release");
            }
          }));
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private enum TestDialect implements Dialect {
    INSTANCE;
    private final SqlRenderer renderer = new StandardSqlRenderer(this);
    @Override public String id() { return "id-test"; }
    @Override public IdentifierRules identifierRules() { return StandardIdentifierRules.INSTANCE; }
    @Override public DialectCapabilities capabilities() { return DialectCapabilities.of(); }
    @Override public SqlRenderer renderer() { return renderer; }
  }
}
