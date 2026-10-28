package io.skis.mutation.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.spi.CompilationCompletedEvent;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionOutcome;
import io.skis.dialect.spi.*;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.*;
import io.skis.metadata.*;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.internal.MutationQuerySupport;
import io.skis.query.spi.ProjectionMapping;
import io.skis.sql.ast.*;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DynamicMutationPlansTest {
  @Test
  void countPlansDoNotCreateDecodersAndUnstableDialectsBypassSharedCaching() {
    var model = model(LoaderValue.class, "value");
    var table = new Table(model.entity());
    var registry = EntityRuntimeRegistry.of(List.of(model));
    var plans = new DynamicMutationPlans(registry, new TestDialect());
    var factories = new AtomicInteger();
    var mapping = mapping(factories);
    var inputs = MutationQuerySupport.inputs(registry, table, List.of(), null, false);
    var statement = new DeleteStatement(MutationQuerySupport.table(table), inputs.where());
    plans.count(new DynamicMutationPlans.Request<>(), "delete", statement, inputs, null);
    assertEquals(0, factories.get());
    plan(plans, table, mapping, new DynamicMutationPlans.Request<>());
    assertEquals(1, factories.get());
    assertEquals(2, plans.statistics().size());
    var unstable = new DynamicMutationPlans(registry, new TestDialect(false));
    var request = new DynamicMutationPlans.Request<DynamicMutationPlans.ConditionalPlan<String>>();
    assertNotSame(plan(unstable, table, mapping, request), plan(unstable, table, mapping, request));
    assertEquals(new io.skis.mutation.MutationPlanCacheStatistics(0, 0, 0, 0, 0, 0), unstable.statistics());
  }

  @Test
  void equivalentRequestsSharePlansWhileBindingTheirOwnValues() throws Exception {
    var model = model(LoaderValue.class, "value");
    var plans = new DynamicMutationPlans(EntityRuntimeRegistry.of(List.of(model)), new TestDialect());
    var table = new Table(model.entity());
    var first = MutationQuerySupport.inputs(plans.registry(), table, List.of(), table.id().eq(1L), false);
    var second = MutationQuerySupport.inputs(plans.registry(), table, List.of(), table.id().eq(2L), false);
    var a = plans.count(new DynamicMutationPlans.Request<>(), "delete",
        new DeleteStatement(MutationQuerySupport.table(table), first.where()), first, null);
    var b = plans.count(new DynamicMutationPlans.Request<>(), "delete",
        new DeleteStatement(MutationQuerySupport.table(table), second.where()), second, null);
    assertSame(a, b);
    var bound = new ArrayList<Long>();
    var statement = (PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
        PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
        (proxy, method, args) -> { assertEquals("setLong", method.getName()); bound.add((Long) args[1]); return null; });
    assertEquals(2, a.parameterBinder().bind(statement, 1, first.values(), JdbcWriteContext.EMPTY));
    assertEquals(2, b.parameterBinder().bind(statement, 1, second.values(), JdbcWriteContext.EMPTY));
    assertEquals(List.of(1L, 2L), bound);
  }

  @Test
  void liveSharedPlansDoNotRetainTemporaryTablesOrRequestGraphs() throws Exception {
    var fixture = cacheTemporaryRequests();
    assertEquals(2, fixture.plans().statistics().size());
    assertCollected(fixture.references());

    var table = new Table(fixture.entity());
    var inputs = MutationQuerySupport.inputs(
        fixture.plans().registry(), table, List.of(), table.id().eq(29L), false);
    var statement = new DeleteStatement(MutationQuerySupport.table(table), inputs.where());
    assertSame(fixture.count(), fixture.plans().count(
        new DynamicMutationPlans.Request<>(), "delete", statement, inputs, null));
    var reused = fixture.plans().conditional(
        new DynamicMutationPlans.Request<>(), "delete", statement, inputs,
        MutationQuerySupport.result(table, fixture.mapping().bind(table.name())), null);
    assertSame(fixture.returning(), reused);
    assertEquals("value", reused.decoder().decode(null, RowReadContext.EMPTY));
    assertEquals(2, fixture.plans().statistics().size());
    assertEquals(2, fixture.plans().statistics().hitCount());
    Reference.reachabilityFence(fixture);
  }

  @Test
  void detachedStructuresPreserveStatementExpressionAndParameterIdentity() throws Exception {
    var table = new Table(model(LoaderValue.class, "value").entity());
    var id = MutationQuerySupport.column(table, table.id());
    var name = MutationQuerySupport.column(table, table.name());
    var key = new ParameterSlot<>(0, Long.class, false);
    var text = new ParameterSlot<>(0, String.class, true);
    var insertedName = new ParameterSlot<>(1, String.class, false);
    var literal = LiteralExpression.one(Long.class);
    var predicate = id.eq(key);
    var statements = new ArrayList<StatementAst>();
    var delete = new DeleteStatement(MutationQuerySupport.table(table), predicate);
    statements.add(delete);
    statements.add(new ReturningStatement(delete, List.of(id, name)));
    statements.add(new ReturningStatement(delete, List.of(name, id)));
    statements.add(new ReturningStatement(delete, List.of(id)));
    statements.add(new ReturningStatement(delete, List.of(id, name), ReturningStatement.Form.OLD_TABLE));
    var update = new UpdateStatement(MutationQuerySupport.table(table),
        List.of(new UpdateAssignment<>(name, name)), predicate);
    statements.add(update);
    statements.add(new ReturningStatement(update, List.of(id), ReturningStatement.Form.FINAL_TABLE));
    statements.add(new UpdateStatement(MutationQuerySupport.table(table),
        List.of(new UpdateAssignment<>(id, literal), new UpdateAssignment<>(name, name)), predicate));
    statements.add(new UpdateStatement(MutationQuerySupport.table(table),
        List.of(new UpdateAssignment<>(name, name), new UpdateAssignment<>(id, literal)), predicate));
    var insert = new InsertStatement(MutationQuerySupport.table(table), List.of(id, name), List.of(key, insertedName));
    statements.add(insert);
    statements.add(new ReturningStatement(insert, List.of(name, id)));
    statements.add(new InsertStatement(MutationQuerySupport.table(table), List.of(name, id), List.of(insertedName, key)));
    statements.add(new InsertStatement(MutationQuerySupport.table(table), List.of(), List.of()));

    List<SqlExpression<?>> expressions = List.of(
        id, name, key, text, new ParameterSlot<>(0, String.class, false),
        new BooleanPredicate(true), new BooleanPredicate(false),
        LiteralExpression.nullLiteral(String.class), LiteralExpression.trueLiteral(), literal,
        new ArithmeticExpression<>(id, ArithmeticOperator.ADD, literal),
        new ArithmeticExpression<>(id, ArithmeticOperator.SUBTRACT, literal),
        new IncrementExpression<>(id),
        new ConcatExpression(List.of(name, text)),
        new CoalesceExpression<>(List.of(text, name)),
        new CaseExpression<>(List.of(new CaseWhen<>(new BooleanPredicate(true), name))),
        new CaseExpression<>(List.of(new CaseWhen<>(new BooleanPredicate(true), name)), text),
        new CastExpression<>(id, BigDecimal.class, SqlType.DECIMAL),
        new CastExpression<>(id, BigDecimal.class, SqlType.DECIMAL, new DecimalPrecision(20, 0)),
        new ComparisonPredicate<>(id, ComparisonOperator.EQUAL, literal),
        new ComparisonPredicate<>(id, ComparisonOperator.NOT_EQUAL, literal),
        LogicalPredicate.and(List.of(new BooleanPredicate(true), new BooleanPredicate(false))),
        LogicalPredicate.or(List.of(new BooleanPredicate(true), new BooleanPredicate(false))),
        new NullPredicate(name, NullOperator.IS_NULL), new NullPredicate(name, NullOperator.IS_NOT_NULL),
        new BetweenPredicate<>(id, LiteralExpression.zero(Long.class), literal),
        new LikePredicate(name, text),
        new InPredicate<>(id, List.of(literal), false), new InPredicate<>(id, List.of(literal), true),
        new InPredicate<>(id, List.of(), false), new NotPredicate(new BooleanPredicate(true)));
    for (var expression : expressions) {
      statements.add(new DeleteStatement(MutationQuerySupport.table(table),
          new NullPredicate(expression, NullOperator.IS_NULL)));
    }
    var other = table.as("other");
    statements.add(new DeleteStatement(MutationQuerySupport.table(other),
        MutationQuerySupport.column(other, other.id()).eq(key)));
    // Independent occurrences with identical descriptors must still share a key.
    var equivalent = new Table(table.entity());
    statements.add(new DeleteStatement(MutationQuerySupport.table(equivalent),
        MutationQuerySupport.column(equivalent, equivalent.id()).eq(new ParameterSlot<>(0, Long.class, false))));

    var structures = statements.stream().map(MutationStructureKey::of).toList();
    for (int first = 0; first < statements.size(); first++) {
      assertDetached(structures.get(first));
      for (int second = 0; second < statements.size(); second++) {
        assertEquals(statements.get(first).equals(statements.get(second)),
            structures.get(first).equals(structures.get(second)), first + "/" + second);
      }
    }
    assertThrows(UnsupportedOperationException.class, () -> structures.getFirst().clear());
    assertThrows(IllegalArgumentException.class, () -> MutationStructureKey.of(new StatementAst() {}));
  }

  @Test
  void returningSourcesCodecsAndSameNamedLoaderTypesDoNotShareDecoders() throws Exception {
    Class<?> firstType = loaderType();
    Class<?> secondType = loaderType();
    assertEquals(firstType.getName(), secondType.getName());
    assertNotSame(firstType, secondType);
    var first = model(firstType, "first");
    var second = model(secondType, "second");
    var registry = EntityRuntimeRegistry.of(List.of(first, second));
    var plans = new DynamicMutationPlans(registry, new TestDialect());
    var mapping = mapping(new AtomicInteger());
    var firstTable = new Table(first.entity());
    var secondTable = new Table(second.entity());
    var a = plan(plans, firstTable, mapping, new DynamicMutationPlans.Request<>());
    var b = plan(plans, secondTable, mapping, new DynamicMutationPlans.Request<>());
    assertNotSame(a, b);
    assertEquals("first", a.decoder().decode(null, RowReadContext.EMPTY));
    assertEquals("second", b.decoder().decode(null, RowReadContext.EMPTY));
    assertSame(a, plan(plans, new Table(first.entity()), mapping, new DynamicMutationPlans.Request<>()));
    assertEquals(2, plans.statistics().size());
  }

  @Test
  void concurrentMissesCompileOnceAndReusedLookupsSkipAllCompilationStages() throws Exception {
    var model = model(LoaderValue.class, "value");
    var dialect = new TestDialect();
    var plans = new DynamicMutationPlans(EntityRuntimeRegistry.of(List.of(model)), dialect);
    var table = new Table(model.entity());
    var factories = new AtomicInteger();
    var mapping = mapping(factories);
    var request = new DynamicMutationPlans.Request<DynamicMutationPlans.ConditionalPlan<String>>();
    var tasks = new ArrayList<Callable<Object>>();
    for (int index = 0; index < 32; index++) { tasks.add(() -> plan(plans, table, mapping, request)); }
    Object first;
    try (var executor = Executors.newFixedThreadPool(8)) {
      var futures = executor.invokeAll(tasks);
      first = futures.getFirst().get();
      for (var future : futures) { assertSame(first, future.get()); }
    }
    int validated = dialect.validations.get();
    assertTrue(validated > 0);
    assertSame(first, plan(plans, table, mapping, request));
    assertSame(first, plan(plans, table, mapping, new DynamicMutationPlans.Request<>()));
    assertEquals(validated, dialect.validations.get());
    assertEquals(1, factories.get());
    plans.clear();
    assertEquals(0, plans.statistics().size());
    assertEquals(1, plans.statistics().invalidationCount());
    assertNotSame(first, plan(plans, table, mapping, request));
    assertEquals(2, factories.get());
  }

  @Test
  void compilationNotificationsRunOutsideCacheLocksOnSuccessAndFailure() throws Exception {
    for (boolean failing : List.of(false, true)) {
      var model = model(LoaderValue.class, "value");
      var dialect = new TestDialect();
      var plans = new DynamicMutationPlans(EntityRuntimeRegistry.of(List.of(model)), dialect);
      var table = new Table(model.entity());
      var inputs = MutationQuerySupport.inputs(plans.registry(), table, List.of(), null, false);
      var statement = new DeleteStatement(MutationQuerySupport.table(table), inputs.where());
      var locksField = DynamicMutationPlans.class.getDeclaredField("compilationLocks");
      locksField.setAccessible(true);
      var locks = (ReentrantLock[]) locksField.get(plans);
      var maintenanceField = DynamicMutationPlans.class.getDeclaredField("maintenance");
      maintenanceField.setAccessible(true);
      Object maintenance = maintenanceField.get(plans);
      var events = new ArrayList<CompilationCompletedEvent>();
      var dispatcher = ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {
        @Override public void compilationCompleted(CompilationCompletedEvent event) {
          for (var lock : locks) {
            assertFalse(lock.isHeldByCurrentThread(), "listener must run after compilation unlock");
          }
          assertFalse(Thread.holdsLock(maintenance), "listener must run after publication unlock");
          assertEquals(failing ? 0 : 1, plans.statistics().size());
          events.add(event);
        }
      }));
      var observation = dispatcher.startOperation(ExecutionOperationKind.DELETE, dialect.id());
      var request = new DynamicMutationPlans.Request<io.skis.jdbc.internal.CompiledMutationPlan<@Nullable Object[]>>();
      var failure = new IllegalStateException("compilation failed");
      if (failing) {
        dialect.beforeValidation = () -> { throw failure; };
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> plans.count(request, "delete", statement, inputs, observation)));
      } else {
        var first = plans.count(request, "delete", statement, inputs, observation);
        assertSame(first, plans.count(request, "delete", statement, inputs, observation));
      }
      assertEquals(1, events.size(), "cache hits must not emit compilation notifications");
      assertEquals(failing ? ExecutionOutcome.FAILED : ExecutionOutcome.SUCCEEDED,
          events.getFirst().outcome());
    }
  }

  @Test
  void blockedCompilationListenerDoesNotBlockAnotherRequestForThePublishedPlan() throws Exception {
    var model = model(LoaderValue.class, "value");
    var dialect = new TestDialect();
    var plans = new DynamicMutationPlans(EntityRuntimeRegistry.of(List.of(model)), dialect);
    var table = new Table(model.entity());
    var inputs = MutationQuerySupport.inputs(plans.registry(), table, List.of(), null, false);
    var statement = new DeleteStatement(MutationQuerySupport.table(table), inputs.where());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var dispatcher = ExecutionListenerDispatcher.of(List.of(new ExecutionListener() {
      @Override public void compilationCompleted(CompilationCompletedEvent event) {
        entered.countDown();
        try {
          assertTrue(release.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new AssertionError(failure);
        }
      }
    }));
    var observation = dispatcher.startOperation(ExecutionOperationKind.DELETE, dialect.id());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var owner = executor.submit(() -> plans.count(
          new DynamicMutationPlans.Request<>(), "delete", statement, inputs, observation));
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        int validations = dialect.validations.get();
        var waiter = executor.submit(() -> plans.count(
            new DynamicMutationPlans.Request<>(), "delete", statement, inputs, null));
        var reused = waiter.get(10, TimeUnit.SECONDS);
        assertEquals(validations, dialect.validations.get());
        assertFalse(owner.isDone());
        release.countDown();
        assertSame(reused, owner.get(10, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void clearPreventsOldCompilationsFromPublishingAndCapacityIsBounded() throws Exception {
    var model = model(LoaderValue.class, "value");
    var dialect = new TestDialect();
    var plans = new DynamicMutationPlans(EntityRuntimeRegistry.of(List.of(model)), dialect);
    var table = new Table(model.entity());
    var mapping = mapping(new AtomicInteger());
    var entered = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    dialect.beforeValidation = () -> {
      entered.countDown();
      try { assertTrue(resume.await(10, TimeUnit.SECONDS)); }
      catch (InterruptedException failure) { throw new AssertionError(failure); }
    };
    try (var executor = Executors.newSingleThreadExecutor()) {
      var future = executor.submit(() -> plan(plans, table, mapping, new DynamicMutationPlans.Request<>()));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      plans.clear();
      dialect.beforeValidation = null;
      resume.countDown();
      assertNotNull(future.get(10, TimeUnit.SECONDS));
      assertEquals(0, plans.statistics().size());
    } finally { resume.countDown(); }
    var creations = new AtomicInteger();
    for (int index = 0; index < 520; index++) {
      plan(plans, table, mapping(creations, "return-name-" + index),
          new DynamicMutationPlans.Request<>());
    }
    assertEquals(520, creations.get());
    assertEquals(512, plans.statistics().size());
    assertEquals(8, plans.statistics().evictionCount());
    plans.clear();
    assertEquals(0, plans.statistics().size());
  }

  private static RetentionFixture cacheTemporaryRequests() {
    var model = model(LoaderValue.class, "value");
    var plans = new DynamicMutationPlans(EntityRuntimeRegistry.of(List.of(model)), new TestDialect());
    var mapping = mapping(new AtomicInteger());
    var table = new Table(model.entity());
    var condition = table.id().eq(17L);
    var inputs = MutationQuerySupport.inputs(plans.registry(), table, List.of(), condition, false);
    var statement = new DeleteStatement(MutationQuerySupport.table(table), inputs.where());
    var selection = mapping.bind(table.name());
    var result = MutationQuerySupport.result(table, selection);
    var countRequest = new DynamicMutationPlans.Request<io.skis.jdbc.internal.CompiledMutationPlan<@Nullable Object[]>>();
    var returningRequest = new DynamicMutationPlans.Request<DynamicMutationPlans.ConditionalPlan<String>>();
    var count = plans.count(countRequest, "delete", statement, inputs, null);
    var returning = plans.conditional(returningRequest, "delete", statement, inputs, result, null);
    return new RetentionFixture(plans, model.entity(), count, returning, mapping,
        List.of(new WeakReference<>(table), new WeakReference<>(condition), new WeakReference<>(inputs),
            new WeakReference<>(statement), new WeakReference<>(selection), new WeakReference<>(result),
            new WeakReference<>(countRequest), new WeakReference<>(returningRequest)));
  }

  private static void assertCollected(List<WeakReference<?>> references) {
    for (int attempt = 0; attempt < 120 && references.stream().anyMatch(reference -> reference.get() != null); attempt++) {
      byte[][] pressure = new byte[8][];
      for (int index = 0; index < pressure.length; index++) {
        pressure[index] = new byte[256 * 1024];
      }
      System.gc();
      Reference.reachabilityFence(pressure);
      LockSupport.parkNanos(5_000_000L);
    }
    assertTrue(references.stream().allMatch(reference -> reference.get() == null),
        "live shared plans must release temporary tables, ASTs and request graphs without clear");
  }

  private static void assertDetached(Object value) throws Exception {
    assertFalse(value instanceof StatementAst || value instanceof SqlExpression<?>
        || value instanceof QueryTable<?> || value instanceof EntityMeta<?>
        || value instanceof PropertyMeta<?, ?> || value instanceof Class<?>);
    if (value instanceof List<?> list) {
      for (Object item : list) { assertDetached(item); }
    } else if (value instanceof Optional<?> optional) {
      if (optional.isPresent()) { assertDetached(optional.orElseThrow()); }
    } else if (value.getClass().isRecord()) {
      for (var component : value.getClass().getRecordComponents()) {
        var accessor = component.getAccessor();
        accessor.setAccessible(true);
        assertDetached(Objects.requireNonNull(accessor.invoke(value)));
      }
    }
  }

  private record RetentionFixture(
      DynamicMutationPlans plans,
      EntityMeta<Object> entity,
      io.skis.jdbc.internal.CompiledMutationPlan<@Nullable Object[]> count,
      DynamicMutationPlans.ConditionalPlan<String> returning,
      ProjectionMapping<String> mapping,
      List<WeakReference<?>> references) {}

  private static DynamicMutationPlans.ConditionalPlan<String> plan(DynamicMutationPlans plans,
      Table table, ProjectionMapping<String> mapping,
      DynamicMutationPlans.Request<DynamicMutationPlans.ConditionalPlan<String>> request) {
    var statement = new DeleteStatement(MutationQuerySupport.table(table), new BooleanPredicate(true));
    var inputs = MutationQuerySupport.inputs(plans.registry(), table, List.of(), null, false);
    return plans.conditional(request, "delete", statement, inputs,
        MutationQuerySupport.result(table, mapping.bind(table.name())), null);
  }

  private static ProjectionMapping<String> mapping(AtomicInteger creations) {
    return mapping(creations, "return-name");
  }

  private static ProjectionMapping<String> mapping(AtomicInteger creations, String mappingId) {
    return ProjectionMapping.generated(GeneratedModelAbi.CURRENT, String.class, mappingId,
        List.of(new ProjectionMapping.Parameter(0, "name", String.class, Nullability.NON_NULL, 0)),
        readers -> {
          creations.incrementAndGet();
          ProjectionMapping.ValueReader<String> read = readers.reader(0, String.class);
          return read::read;
        });
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static EntityRuntimeModel<Object> model(Class<?> type, String decoded) {
    var id = new PropertyMeta<Object, Long>(0, "id", Long.class, ColumnMeta.of("id", false));
    var name = new PropertyMeta<Object, String>(1, "name", String.class, ColumnMeta.of("name", false));
    EntityMeta<Object> metadata = EntityMeta.simple((Class) type, TableMeta.of("same_table"),
        List.of(id, name), new PrimaryKeyMeta<>(List.of(id)), false);
    JdbcTypeCodec<String> codec = new JdbcTypeCodec<>() {
      @Override public String read(ResultSet rs, int index, JdbcReadContext context) { return decoded; }
      @Override public void bind(PreparedStatement ps, int index, @Nullable String value, JdbcWriteContext context) {}
    };
    return new EntityRuntimeModel<>(metadata, layout -> (rs, context) -> new Object(),
        List.of(new PropertyRuntime<>(id, JdbcCodecs.LONG), new PropertyRuntime<>(name, codec)));
  }

  private static Class<?> loaderType() throws Exception {
    String name = LoaderValue.class.getName();
    byte[] bytes;
    try (var input = Objects.requireNonNull(LoaderValue.class.getResourceAsStream("/" + name.replace('.', '/') + ".class"))) {
      bytes = input.readAllBytes();
    }
    class Loader extends ClassLoader {
      Loader() { super(LoaderValue.class.getClassLoader()); }
      Class<?> define() { return defineClass(name, bytes, 0, bytes.length); }
    }
    return new Loader().define();
  }

  private static final class Table extends QueryTable<Object> {
    Table(EntityMeta<Object> metadata) { super(metadata); }
    Table(EntityMeta<Object> metadata, String alias) { super(metadata, alias); }
    @Override public Table as(String alias) { return new Table(entity(), alias); }
    @SuppressWarnings("unchecked")
    Selectable<String> name() { return (Selectable<String>) columns().get(1); }
    @SuppressWarnings("unchecked")
    Selectable<Long> id() { return (Selectable<Long>) columns().getFirst(); }
  }

  private static final class TestDialect implements Dialect {
    final AtomicInteger validations = new AtomicInteger();
    volatile @Nullable Runnable beforeValidation;
    private final SqlRenderer renderer = new StandardSqlRenderer(this);
    private final boolean stable;
    TestDialect() { this(true); }
    TestDialect(boolean stable) { this.stable = stable; }
    @Override public String id() { return "cache-test"; }
    @Override public boolean hasStablePlanCacheIdentity() { return stable; }
    @Override public IdentifierRules identifierRules() { return StandardIdentifierRules.INSTANCE; }
    @Override public DialectCapabilities capabilities() { return DialectCapabilities.of(DialectFeature.MUTATION_RETURNING); }
    @Override public SqlRenderer renderer() { return renderer; }
    @Override public void validate(ResolvedStatement statement) {
      validations.incrementAndGet();
      Runnable action = beforeValidation;
      if (action != null) { action.run(); }
      Dialect.super.validate(statement);
    }
  }
}
