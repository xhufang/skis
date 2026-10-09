package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardIdentifierRules;
import io.skis.dialect.StandardSqlRenderer;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mapping.JdbcCodecs;
import io.skis.mapping.PropertyRuntime;
import io.skis.mapping.RowDecoder;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.Identifier;
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

    assertEquals(new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 4),
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
    DefaultSelectQuery<RetentionEntity, RetentionView> query =
        (DefaultSelectQuery<RetentionEntity, RetentionView>) QueryTestSupport.operations(catalog)
            .select(selection).from(table).where(condition);
    QueryCompilation<RetentionView> compilation =
        query.compilation(QueryPagination.None.INSTANCE);
    references.addAll(List.of(
            tracked("query " + label, query),
            tracked("compilation " + label, compilation),
            tracked("AST", compilation.ast()),
            tracked("argument container", compilation.argument()),
            tracked("argument value", conditionValue),
            tracked("condition", condition),
            tracked("table", table),
            tracked("table marker", tableMarker),
            tracked("selected column", table.id()),
            tracked("column expression", table.id().expression()),
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
            List.of(
                new ProjectionMapping.Parameter(
                    0, "id", Long.class, Nullability.NON_NULL, 0)),
            ignored -> decoder);
    ProjectionSelection<Object> selection = mapping.bind(KEY_TABLE.id());
    QueryPlanCatalog catalog =
        new QueryPlanCatalog(REGISTRY, TestDialect.INSTANCE, 4, Duration.ofMinutes(1));
    QueryOperations operations = QueryTestSupport.operations(catalog);
    DefaultSelectQuery<RetentionEntity, Object> first =
        (DefaultSelectQuery<RetentionEntity, Object>) operations.select(selection).from(KEY_TABLE);
    DefaultSelectQuery<RetentionEntity, Object> second =
        (DefaultSelectQuery<RetentionEntity, Object>) operations.select(selection).from(KEY_TABLE);
    assertSame(first.compilation(QueryPagination.None.INSTANCE).plan(),
        second.compilation(QueryPagination.None.INSTANCE).plan());
    assertEquals(new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 4),
        catalog.projectionPlanCacheStatistics());
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
              new RetentionView(
                  id.read(resultSet, context), name.read(resultSet, context));
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
      System.runFinalization();
      Reference.reachabilityFence(pressure);
      LockSupport.parkNanos(5_000_000L);
    }
  }

  private static boolean hasLiveReference(List<TrackedReference> references) {
    return references.stream().anyMatch(reference -> reference.reference().get() != null);
  }

  private record InvocationFixture(
      QueryPlanCatalog catalog, CachedQueryPlan<RetentionView> cachedPlan,
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
    private final NonNullQueryColumn<RetentionEntity, Long> id = nonNullQueryColumn(ID);
    private final NonNullQueryColumn<RetentionEntity, String> name = nonNullQueryColumn(NAME);

    private RetentionTable(Object marker) {
      super(ENTITY);
      this.marker = marker;
    }

    private RetentionTable(Object marker, Identifier alias) {
      super(ENTITY, alias);
      this.marker = marker;
    }

    private NonNullQueryColumn<RetentionEntity, Long> id() {
      return id;
    }

    private NonNullQueryColumn<RetentionEntity, String> name() {
      return name;
    }

    @Override
    public RetentionTable as(Identifier alias) {
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
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    private final SqlRenderer renderer =
        new StandardSqlRenderer(id(), identifierRules(), capabilities);

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
