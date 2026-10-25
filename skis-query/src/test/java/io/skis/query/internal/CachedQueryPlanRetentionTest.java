package io.skis.query.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.CompilationCompletedEvent;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.OperationCompletedEvent;
import io.skis.core.spi.OperationStartedEvent;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.mapping.spi.RowDecoder;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.QueryCondition;
import io.skis.query.QueryOperations;
import io.skis.query.QueryPlanCacheStatistics;
import io.skis.query.QueryTable;
import io.skis.query.spi.ProjectionMapping;
import io.skis.sql.ast.Nullability;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class CachedQueryPlanRetentionTest {

  private static final PropertyMeta<RetentionEntity, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<RetentionEntity, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("name", false));
  private static final EntityMeta<RetentionEntity> ENTITY =
      EntityMeta.simple(
          RetentionEntity.class,
          new TableMeta("", "retention_test", "entity"),
          List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);
  private static final EntityRuntimeRegistry REGISTRY =
      EntityRuntimeRegistry.of(List.of(runtimeModel()));
  private static final RetentionTable KEY_TABLE = new RetentionTable(null);

  @Test
  void liveCatalogAndCachedPlanDoNotRetainMissOrHitInvocationGraphs() {
    assertEquals(
        List.of("plan"),
        Arrays.stream(CachedQueryPlan.class.getRecordComponents())
            .map(component -> component.getName())
            .toList());
    InvocationFixture fixture = compileInvocationFixture();

    assertCollected(fixture.references());

    assertEquals(
        new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 4),
        fixture.catalog().projectionPlanCacheStatistics());
    Reference.reachabilityFence(fixture.catalog());
    Reference.reachabilityFence(fixture.cachedPlan());
  }

  @Test
  void explicitClearReleasesAResultDecoderClassLoader() throws IOException {
    LoaderFixture fixture = cacheLoaderScopedDecoder();

    encourageCollection(fixture.references(), 12);
    assertNotNull(fixture.reference("loader").reference().get());
    assertNotNull(fixture.reference("result class").reference().get());
    assertNotNull(fixture.reference("row decoder").reference().get());

    fixture.catalog().clearProjectionPlans();

    assertCollected(fixture.references());
    Reference.reachabilityFence(fixture.catalog());
  }

  @Test
  void cachedObservedCountDoesNotRetainListenersEventsOrInvocationState() {
    ObservedFixture fixture = compileObservedFixture();

    assertCollected(fixture.references());

    assertEquals(
        new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 4),
        fixture.catalog().projectionPlanCacheStatistics());
    Reference.reachabilityFence(fixture.catalog());
    Reference.reachabilityFence(fixture.cachedPlan());
  }

  private static ObservedFixture compileObservedFixture() {
    QueryPlanCatalog catalog =
        new QueryPlanCatalog(REGISTRY, TestDialect.INSTANCE, 4, Duration.ofMinutes(1));
    List<TrackedReference> references = new ArrayList<>();
    CachedQueryPlan<Long> first = compileObservedCount(catalog, "first", references);
    CachedQueryPlan<Long> second = compileObservedCount(catalog, "second", references);
    assertSame(first.plan(), second.plan());
    return new ObservedFixture(catalog, first, List.copyOf(references));
  }

  private static CachedQueryPlan<Long> compileObservedCount(
      QueryPlanCatalog catalog, String label, List<TrackedReference> references) {
    List<Object> events = new ArrayList<>();
    ExecutionListener listener =
        new ExecutionListener() {
          @Override
          public void operationStarted(OperationStartedEvent event) {
            events.add(event);
          }

          @Override
          public void compilationCompleted(CompilationCompletedEvent event) {
            events.add(event);
          }

          @Override
          public void operationCompleted(OperationCompletedEvent event) {
            events.add(event);
          }
        };
    ExecutionListenerDispatcher dispatcher = ExecutionListenerDispatcher.of(List.of(listener));
    OperationObservation observation =
        java.util.Objects.requireNonNull(
            dispatcher.startOperation(ExecutionOperationKind.FETCH_PAGE, TestDialect.INSTANCE.id()));
    RetentionTable table = new RetentionTable(new Object());
    String value = new String("observed-value-" + label);
    DefaultSelectQuery<RetentionEntity> query =
        (DefaultSelectQuery<RetentionEntity>)
            QueryTestSupport.operations(catalog).selectFrom(table).where(table.name().eq(value));
    QueryCompilation<Long> compilation = query.countCompilation(observation);
    observation.completeSucceeded(ExecutionTermination.COMPLETED);
    assertEquals(label.equals("first") ? 3 : 2, events.size());
    references.addAll(
        List.of(
            tracked("listener " + label, listener),
            tracked("dispatcher " + label, dispatcher),
            tracked("observation " + label, observation),
            tracked("event list " + label, events),
            tracked("observed query " + label, query),
            tracked("observed compilation " + label, compilation),
            tracked("observed AST " + label, compilation.ast()),
            tracked("observed arguments " + label, compilation.argument()),
            tracked("observed value " + label, value),
            tracked("observed table " + label, table)));
    for (Object event : events) {
      references.add(tracked("event " + label, event));
    }
    return CachedQueryPlan.from(compilation);
  }

  private static InvocationFixture compileInvocationFixture() {
    QueryPlanCatalog catalog =
        new QueryPlanCatalog(REGISTRY, TestDialect.INSTANCE, 4, Duration.ofMinutes(1));
    List<TrackedReference> references = new ArrayList<>();
    CachedQueryPlan<RetentionView> first = compileInvocation(catalog, "first", references);
    CachedQueryPlan<RetentionView> second = compileInvocation(catalog, "second", references);
    assertSame(first.plan(), second.plan());
    return new InvocationFixture(catalog, first, List.copyOf(references));
  }

  private static CachedQueryPlan<RetentionView> compileInvocation(
      QueryPlanCatalog catalog, String label, List<TrackedReference> references) {
    Object tableMarker = new Object();
    RetentionTable table = new RetentionTable(tableMarker);
    String conditionValue = new String("invocation-only-value-" + label);
    QueryCondition condition = table.name().eq(conditionValue);
    ProjectionMapping<RetentionView> mapping = projectionMapping();
    ProjectionSelection<RetentionView> selection = mapping.bind(table.id(), table.name());
    DefaultSelectQuery<RetentionView> query =
        (DefaultSelectQuery<RetentionView>)
            QueryTestSupport.operations(catalog).select(selection).from(table).where(condition);
    QueryCompilation<RetentionView> compilation = query.compilation(QueryPagination.None.INSTANCE);
    references.addAll(
        List.of(
            tracked("query " + label, query),
            tracked("compilation " + label, compilation),
            tracked("AST", compilation.ast()),
            tracked("argument container", compilation.argument()),
            tracked("argument value", conditionValue),
            tracked("condition", condition),
            tracked("table", table),
            tracked("table marker", tableMarker),
            tracked("selected column", table.id()),
            tracked("column expression", SelectableSupport.expression(table.id())),
            tracked("projection mapping", mapping),
            tracked("projection selection", selection)));
    return CachedQueryPlan.from(compilation);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static LoaderFixture cacheLoaderScopedDecoder() throws IOException {
    byte[] bytes = classBytes(LoaderScopedResult.class);
    ChildLoader loader = new ChildLoader();
    Class<?> loadedResultType = loader.define(LoaderScopedResult.class.getName(), bytes);
    RowDecoder<Object> decoder =
        (RowDecoder<Object>)
            Proxy.newProxyInstance(
                loader,
                new Class<?>[] {RowDecoder.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("decode")) {
                    return null;
                  }
                  return switch (method.getName()) {
                    case "toString" -> "loader-scoped-row-decoder";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                  };
                });
    Class<Object> resultType = (Class<Object>) loadedResultType;
    ProjectionMapping<Object> mapping =
        ProjectionMapping.generated(
            GeneratedModelAbi.CURRENT,
            resultType,
            "loader-scoped-retention",
            List.of(new ProjectionMapping.Parameter(0, "id", Long.class, Nullability.NON_NULL, 0)),
            ignored -> decoder);
    ProjectionSelection<Object> selection = mapping.bind(KEY_TABLE.id());
    QueryPlanCatalog catalog =
        new QueryPlanCatalog(REGISTRY, TestDialect.INSTANCE, 4, Duration.ofMinutes(1));
    QueryOperations operations = QueryTestSupport.operations(catalog);
    DefaultSelectQuery<Object> first =
        (DefaultSelectQuery<Object>) operations.select(selection).from(KEY_TABLE);
    DefaultSelectQuery<Object> second =
        (DefaultSelectQuery<Object>) operations.select(selection).from(KEY_TABLE);
    assertSame(
        first.compilation(QueryPagination.None.INSTANCE).plan(),
        second.compilation(QueryPagination.None.INSTANCE).plan());
    assertEquals(
        new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 4), catalog.projectionPlanCacheStatistics());
    // Only catalog and weak references leave this method: no query L0 survives the clear barrier.
    List<TrackedReference> references =
        List.of(
            tracked("first query", first),
            tracked("second query", second),
            tracked("loader", loader),
            tracked("result class", loadedResultType),
            tracked("row decoder", decoder),
            tracked("projection mapping", mapping),
            tracked("projection selection", selection));
    return new LoaderFixture(catalog, references);
  }

  private static ProjectionMapping<RetentionView> projectionMapping() {
    return ProjectionMapping.generated(
        GeneratedModelAbi.CURRENT,
        RetentionView.class,
        "retention-view-v1",
        List.of(
            new ProjectionMapping.Parameter(0, "id", Long.class, Nullability.NON_NULL, 0),
            new ProjectionMapping.Parameter(1, "name", String.class, Nullability.NON_NULL, 1)),
        readers -> {
          ProjectionMapping.ValueReader<Long> id = readers.reader(0, Long.class);
          ProjectionMapping.ValueReader<String> name = readers.reader(1, String.class);
          return (resultSet, context) ->
              new RetentionView(id.read(resultSet, context), name.read(resultSet, context));
        });
  }

  private static EntityRuntimeModel<RetentionEntity> runtimeModel() {
    return new EntityRuntimeModel<>(
        ENTITY,
        layout ->
            (resultSet, context) ->
                new RetentionEntity(
                    JdbcCodecs.LONG.read(resultSet, layout.requireIndex(0), context),
                    JdbcCodecs.STRING.read(resultSet, layout.requireIndex(1), context)),
        List.of(
            new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(NAME, JdbcCodecs.STRING)));
  }

  private static byte[] classBytes(Class<?> type) throws IOException {
    String resourceName = "/" + type.getName().replace('.', '/') + ".class";
    try (InputStream input =
        Objects.requireNonNull(type.getResourceAsStream(resourceName), "class resource")) {
      return input.readAllBytes();
    }
  }

  private static TrackedReference tracked(String name, Object value) {
    return new TrackedReference(name, new WeakReference<>(Objects.requireNonNull(value, name)));
  }

  private static void assertCollected(List<TrackedReference> references) {
    encourageCollection(references, 120);
    List<String> retained =
        references.stream()
            .filter(reference -> reference.reference().get() != null)
            .map(TrackedReference::name)
            .toList();
    assertTrue(retained.isEmpty(), () -> "unexpectedly retained objects: " + retained);
  }

  private static void encourageCollection(List<TrackedReference> references, int attempts) {
    for (int attempt = 0; attempt < attempts && hasLiveReference(references); attempt++) {
      byte[][] pressure = new byte[8][];
      for (int index = 0; index < pressure.length; index++) {
        pressure[index] = new byte[256 * 1024];
      }
      System.gc();
      // Weak-reference clearing does not require finalization; keep pressure and waiting bounded.
      Reference.reachabilityFence(pressure);
      LockSupport.parkNanos(5_000_000L);
    }
  }

  private static boolean hasLiveReference(List<TrackedReference> references) {
    return references.stream().anyMatch(reference -> reference.reference().get() != null);
  }

  private record InvocationFixture(
      QueryPlanCatalog catalog,
      CachedQueryPlan<RetentionView> cachedPlan,
      List<TrackedReference> references) {}

  private record ObservedFixture(
      QueryPlanCatalog catalog,
      CachedQueryPlan<Long> cachedPlan,
      List<TrackedReference> references) {}

  private record LoaderFixture(QueryPlanCatalog catalog, List<TrackedReference> references) {

    private TrackedReference reference(String name) {
      return references.stream()
          .filter(reference -> reference.name().equals(name))
          .findFirst()
          .orElseThrow();
    }
  }

  private record TrackedReference(String name, WeakReference<Object> reference) {}

  private record RetentionEntity(Long id, String name) {}

  private record RetentionView(Long id, String name) {}

  private static final class RetentionTable extends QueryTable<RetentionEntity> {

    @SuppressWarnings("unused")
    private final Object marker;

    private final NonNullSelectable<Long> id = nonNullQueryColumn(ID);
    private final NonNullSelectable<String> name = nonNullQueryColumn(NAME);

    private RetentionTable(Object marker) {
      super(ENTITY);
      this.marker = marker;
    }

    private RetentionTable(Object marker, String alias) {
      super(ENTITY, alias);
      this.marker = marker;
    }

    private NonNullSelectable<Long> id() {
      return id;
    }

    private NonNullSelectable<String> name() {
      return name;
    }

    public RetentionTable as(String alias) {
      return new RetentionTable(marker, alias);
    }
  }

  private static final class ChildLoader extends ClassLoader {

    private ChildLoader() {
      super(CachedQueryPlanRetentionTest.class.getClassLoader());
    }

    private Class<?> define(String name, byte[] bytes) {
      return defineClass(name, bytes, 0, bytes.length);
    }
  }

  private enum TestDialect implements Dialect {
    INSTANCE;

    private final DialectCapabilities capabilities =
        DialectCapabilities.of(
            DialectFeature.SCHEMA_QUALIFIED_TABLES, DialectFeature.COUNT_AGGREGATE);
    private final SqlRenderer renderer = new StandardSqlRenderer(this);

    @Override
    public String id() {
      return "retention-test";
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
