package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardIdentifierRules;
import io.skis.dialect.StandardSqlRenderer;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mapping.JdbcCodecs;
import io.skis.mapping.JdbcWriteContext;
import io.skis.mapping.PropertyRuntime;
import io.skis.mapping.RowReadContext;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class CompiledQueryPlanConcurrencyTest {

  private static final int WORKERS = 8;
  private static final int INVOCATIONS = 64;
  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("name", false));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "concurrency_test", "pet"),
          List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);
  private static final EntityRuntimeRegistry REGISTRY =
      EntityRuntimeRegistry.of(List.of(runtimeModel()));

  @Test
  void oneCompiledScalarPlanBindsAndDecodesDistinctConcurrentValues() throws Exception {
    PetTable table = new PetTable();
    CompiledQueryStructure structure =
        QueryTestSupport.compile(
            SelectedResult.requiredScalar(table.id()),
            table,
            List.of(),
            table.name().eq("compile-seed"));
    CompiledQueryPlan<Long, Object> plan =
        compiler()
            .compileSelection(
                SelectedResult.requiredScalar(table.id()),
                structure,
                List.of(),
                false,
                QueryPagination.None.INSTANCE,
                List.of(),
                QueryTestSupport.arguments(structure))
            .plan();

    List<Observation<Long>> observations =
        invokeConcurrently(
            plan,
            index -> "condition-" + index,
            index -> Map.of(1, 10_000L + index));

    for (int index = 0; index < observations.size(); index++) {
      Observation<Long> observed = observations.get(index);
      assertEquals(2, observed.nextParameterIndex());
      assertEquals("condition-" + index, observed.boundValue());
      assertEquals(10_000L + index, observed.decoded());
    }
  }

  @Test
  void oneCompiledProjectionPlanBindsAndDecodesDistinctConcurrentValues() throws Exception {
    PetTable table = new PetTable();
    ProjectionSelection<PetView> selected = projectionMapping().bind(table.id(), table.name());
    CompiledQueryStructure structure =
        QueryTestSupport.compile(
            SelectedResult.projection(selected), table, List.of(), table.id().eq(1L));
    CompiledQueryPlan<PetView, Object> plan =
        compiler()
            .compileSelection(
                SelectedResult.projection(selected),
                structure,
                List.of(),
                false,
                QueryPagination.None.INSTANCE,
                List.of(),
                QueryTestSupport.arguments(structure))
            .plan();

    List<Observation<PetView>> observations =
        invokeConcurrently(
            plan,
            index -> 20_000L + index,
            index -> Map.of(1, 30_000L + index, 2, "pet-" + index));

    IdentityHashMap<PetView, Boolean> decodedInstances = new IdentityHashMap<>();
    for (int index = 0; index < observations.size(); index++) {
      Observation<PetView> observed = observations.get(index);
      assertEquals(2, observed.nextParameterIndex());
      assertEquals(20_000L + index, observed.boundValue());
      assertEquals(new PetView(30_000L + index, "pet-" + index), observed.decoded());
      decodedInstances.put(observed.decoded(), Boolean.TRUE);
    }
    assertEquals(INVOCATIONS, decodedInstances.size());
  }

  @Test
  void oneCompiledOrderedPlanUsesAnIndependentOrderBufferPerConcurrentRow() throws Exception {
    PetTable table = new PetTable();
    CompiledQueryStructure structure =
        QueryTestSupport.compile(
            SelectedResult.requiredScalar(table.name()),
            table,
            List.of(),
            table.name().eq("compile-seed"));
    CompiledQueryPlan<OrderedRow<String>, Object> plan =
        compiler()
            .compileOrdered(
                SelectedResult.requiredScalar(table.name()),
                structure,
                List.of(table.id().asc()),
                false,
                QueryPagination.None.INSTANCE,
                QueryTestSupport.arguments(structure))
            .plan();

    List<Observation<OrderedRow<String>>> observations =
        invokeConcurrently(
            plan,
            index -> "condition-" + index,
            index -> Map.of(1, "ordered-" + index, 2, 40_000L + index));

    IdentityHashMap<List<?>, Boolean> orderBuffers = new IdentityHashMap<>();
    for (int index = 0; index < observations.size(); index++) {
      Observation<OrderedRow<String>> observed = observations.get(index);
      assertEquals(2, observed.nextParameterIndex());
      assertEquals("condition-" + index, observed.boundValue());
      assertEquals("ordered-" + index, observed.decoded().value());
      assertEquals(List.of(40_000L + index), observed.decoded().orderValues());
      orderBuffers.put(observed.decoded().orderValues(), Boolean.TRUE);
    }
    assertEquals(INVOCATIONS, orderBuffers.size());
  }

  @Test
  void freshProjectionQueriesBindAndDecodeTheSameL1PlanConcurrently() throws Exception {
    QueryPlanCatalog catalog =
        new QueryPlanCatalog(REGISTRY, TestDialect.INSTANCE, 4, Duration.ofMinutes(1));
    QueryCompilation<PetView> warmed = projectionQuery(catalog, 1L);
    List<Observation<PetView>> observations = invokeConcurrently(
        index -> new Invocation<>(projectionQuery(catalog, 20_000L + index)),
        index -> Map.of(1, 30_000L + index, 2, "pet-" + index));

    IdentityHashMap<PetView, Boolean> instances = new IdentityHashMap<>();
    for (int index = 0; index < observations.size(); index++) {
      Observation<PetView> observed = observations.get(index);
      assertSame(warmed.plan(), observed.plan());
      assertEquals(2, observed.nextParameterIndex());
      assertEquals(20_000L + index, observed.boundValue());
      assertEquals(new PetView(30_000L + index, "pet-" + index), observed.decoded());
      instances.put(observed.decoded(), Boolean.TRUE);
    }
    assertEquals(INVOCATIONS, instances.size());
    assertEquals(new QueryPlanCacheStatistics(INVOCATIONS, 1, 0, 0, 1, 4),
        catalog.projectionPlanCacheStatistics());
  }

  @Test
  void freshOrderedQueriesBindAndDecodeIndependentHiddenBuffersThroughL1() throws Exception {
    QueryPlanCatalog catalog =
        new QueryPlanCatalog(REGISTRY, TestDialect.INSTANCE, 4, Duration.ofMinutes(1));
    QueryCompilation<OrderedRow<String>> warmed = orderedQuery(catalog, "seed");
    List<Observation<OrderedRow<String>>> observations = invokeConcurrently(
        index -> new Invocation<>(orderedQuery(catalog, "condition-" + index)),
        index -> Map.of(1, "ordered-" + index, 2, 40_000L + index));

    IdentityHashMap<OrderedRow<String>, Boolean> instances = new IdentityHashMap<>();
    IdentityHashMap<List<?>, Boolean> buffers = new IdentityHashMap<>();
    for (int index = 0; index < observations.size(); index++) {
      Observation<OrderedRow<String>> observed = observations.get(index);
      assertSame(warmed.plan(), observed.plan());
      assertEquals(2, observed.nextParameterIndex());
      assertEquals("condition-" + index, observed.boundValue());
      assertEquals("ordered-" + index, observed.decoded().value());
      assertEquals(List.of(40_000L + index), observed.decoded().orderValues());
      instances.put(observed.decoded(), Boolean.TRUE);
      buffers.put(observed.decoded().orderValues(), Boolean.TRUE);
    }
    assertEquals(INVOCATIONS, instances.size());
    assertEquals(INVOCATIONS, buffers.size());
    assertEquals(new QueryPlanCacheStatistics(INVOCATIONS, 1, 0, 0, 1, 4),
        catalog.projectionPlanCacheStatistics());
  }

  private static QueryCompilation<PetView> projectionQuery(QueryPlanCatalog catalog, long value) {
    PetTable table = new PetTable();
    DefaultSelectQuery<Pet, PetView> query =
        (DefaultSelectQuery<Pet, PetView>) QueryTestSupport.operations(catalog)
            .select(projectionMapping().bind(table.id(), table.name())).from(table)
            .where(table.id().eq(value));
    return query.compilation(QueryPagination.None.INSTANCE);
  }

  private static QueryCompilation<OrderedRow<String>> orderedQuery(
      QueryPlanCatalog catalog, String value) {
    PetTable table = new PetTable();
    SelectedResult<String> selected = SelectedResult.requiredScalar(table.name());
    CompiledQueryStructure structure =
        QueryTestSupport.compile(selected, table, List.of(), table.name().eq(value));
    return catalog.compiler().compileOrdered(selected, structure, List.of(table.id().asc()),
        false, QueryPagination.None.INSTANCE, QueryTestSupport.arguments(structure));
  }

  private static <R> List<Observation<R>> invokeConcurrently(
      CompiledQueryPlan<R, Object> plan,
      IntFunction<Object> parameter,
      IntFunction<Map<Integer, Object>> row) throws Exception {
    return invokeConcurrently(
        index -> new Invocation<>(plan, new QueryArguments(List.of(parameter.apply(index)))), row);
  }

  private static <R> List<Observation<R>> invokeConcurrently(
      IntFunction<Invocation<R>> inputs, IntFunction<Map<Integer, Object>> row) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
    CyclicBarrier simultaneousStart = new CyclicBarrier(WORKERS);
    try {
      List<Future<Observation<R>>> futures = new ArrayList<>(INVOCATIONS);
      for (int invocation = 0; invocation < INVOCATIONS; invocation++) {
        int index = invocation;
        futures.add(
            executor.submit(
                () -> {
                  simultaneousStart.await();
                  Invocation<R> current = inputs.apply(index);
                  CompiledQueryPlan<R, Object> plan = current.plan();
                  Map<Integer, Object> bound = new HashMap<>();
                  int next =
                      plan.parameterBinder()
                          .bind(
                              preparedStatement(bound),
                              1,
                              current.argument(),
                              JdbcWriteContext.EMPTY);
                  R decoded =
                      plan.rowDecoder()
                          .decode(resultSet(row.apply(index)), RowReadContext.EMPTY);
                  return new Observation<>(plan, next, bound.get(1), decoded);
                }));
      }
      List<Observation<R>> results = new ArrayList<>(INVOCATIONS);
      for (Future<Observation<R>> future : futures) {
        results.add(future.get(15, TimeUnit.SECONDS));
      }
      return List.copyOf(results);
    } finally {
      executor.shutdownNow();
    }
  }

  private static PreparedStatement preparedStatement(Map<Integer, Object> values) {
    return (PreparedStatement)
        Proxy.newProxyInstance(
            PreparedStatement.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (ignored, method, arguments) -> {
              if (method.getName().startsWith("set")
                  && arguments != null
                  && arguments.length >= 2
                  && arguments[0] instanceof Integer index) {
                values.put(index, method.getName().equals("setNull") ? null : arguments[1]);
                return null;
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static ResultSet resultSet(Map<Integer, Object> values) {
    int[] lastIndex = new int[1];
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (ignored, method, arguments) -> {
              if (method.getName().equals("wasNull")) {
                return values.get(lastIndex[0]) == null;
              }
              if (arguments != null
                  && arguments.length >= 1
                  && arguments[0] instanceof Integer index) {
                lastIndex[0] = index;
                Object value = values.get(index);
                return switch (method.getName()) {
                  case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                  case "getString", "getObject" -> value;
                  default -> defaultValue(method.getReturnType());
                };
              }
              return defaultValue(method.getReturnType());
            });
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
    return 0;
  }

  private static QueryPlanCompiler compiler() {
    return QueryTestSupport.compiler(REGISTRY, TestDialect.INSTANCE);
  }

  private static ProjectionMapping<PetView> projectionMapping() {
    return ProjectionMapping.generated(
        GeneratedModelAbi.CURRENT,
        PetView.class,
        "concurrent-pet-view-v1",
        List.of(
            new ProjectionMapping.Parameter(0, "id", Long.class, Nullability.NON_NULL, 0),
            new ProjectionMapping.Parameter(1, "name", String.class, Nullability.NON_NULL, 1)),
        readers -> {
          ProjectionMapping.ValueReader<Long> id = readers.reader(0, Long.class);
          ProjectionMapping.ValueReader<String> name = readers.reader(1, String.class);
          return (resultSet, context) ->
              new PetView(id.read(resultSet, context), name.read(resultSet, context));
        });
  }

  private static EntityRuntimeModel<Pet> runtimeModel() {
    return new EntityRuntimeModel<>(
        PET,
        layout ->
            (resultSet, context) ->
                new Pet(
                    JdbcCodecs.LONG.read(resultSet, layout.requireIndex(0), context),
                    JdbcCodecs.STRING.read(resultSet, layout.requireIndex(1), context)),
        List.of(
            new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(NAME, JdbcCodecs.STRING)));
  }

  private record Invocation<R>(CompiledQueryPlan<R, Object> plan, Object argument) {
    private Invocation(QueryCompilation<R> compilation) {
      this(compilation.plan(), compilation.argument());
    }
  }

  private record Observation<R>(
      CompiledQueryPlan<R, Object> plan, int nextParameterIndex, Object boundValue, R decoded) {}

  private record Pet(Long id, String name) {}

  private record PetView(Long id, String name) {}

  private static final class PetTable extends QueryTable<Pet> {

    private final NonNullQueryColumn<Pet, Long> id = nonNullQueryColumn(ID);
    private final NonNullQueryColumn<Pet, String> name = nonNullQueryColumn(NAME);

    private PetTable() {
      super(PET);
    }

    private PetTable(Identifier alias) {
      super(PET, alias);
    }

    private NonNullQueryColumn<Pet, Long> id() {
      return id;
    }

    private NonNullQueryColumn<Pet, String> name() {
      return name;
    }

    @Override
    public PetTable as(Identifier alias) {
      return new PetTable(alias);
    }
  }

  private enum TestDialect implements Dialect {
    INSTANCE;

    private final DialectCapabilities capabilities =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    private final SqlRenderer renderer =
        new StandardSqlRenderer(id(), identifierRules(), capabilities);

    @Override
    public String id() {
      return "concurrency-test";
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
    public boolean hasStablePlanCacheIdentity() {
      return true;
    }

    @Override
    public SqlRenderer renderer() {
      return renderer;
    }
  }
}
