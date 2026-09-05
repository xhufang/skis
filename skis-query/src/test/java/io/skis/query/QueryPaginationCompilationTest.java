package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardIdentifierRules;
import io.skis.dialect.StandardSqlRenderer;
import io.skis.core.ExecutionContext;
import io.skis.jdbc.ConnectionProvider;
import io.skis.jdbc.JdbcExecutor;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mapping.JdbcCodecs;
import io.skis.mapping.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.HiddenSelection;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.KeysetSeek;
import io.skis.sql.ast.OffsetLimit;
import io.skis.sql.ast.SelectStatement;
import java.sql.Connection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class QueryPaginationCompilationTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("pet_name", false));
  private static final PropertyMeta<Pet, String> NICKNAME =
      new PropertyMeta<>(2, "nickname", String.class, ColumnMeta.of("nickname", true));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "shelter", "pet"),
          List.of(ID, NAME, NICKNAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);
  private static final PetTable TABLE = new PetTable();

  @Test
  void compilesOffsetContentAndIndependentCountPlans() {
    CompilerFixture fixture = compilerFixture();
    QueryPredicate<Pet> predicate = TABLE.id().ge(10L);
    List<SortSpecification<Pet>> order =
        List.of(TABLE.nickname().desc().nullsLast(), TABLE.id().desc());

    QueryCompilation<Pet> content =
        compileEntity(
            fixture,
            predicate,
            order,
            false,
            new QueryPagination.Offset(20, 40));
    QueryCompilation<Long> count =
        fixture
            .compiler()
            .compileCount(
                fixture.model(),
                TABLE,
                SelectedResult.entity(TABLE, fixture.plans()),
                List.of(),
                predicate,
                false);

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\", \"pet\".\"nickname\" "
            + "FROM \"shelter\".\"pet\" WHERE \"pet\".\"id\" >= ? "
            + "ORDER BY \"pet\".\"nickname\" DESC NULLS LAST, \"pet\".\"id\" DESC "
            + "LIMIT ? OFFSET ?",
        content.plan().sql());
    assertEquals(List.of(10L, 20, 40L), arguments(content));
    assertTrue(((SelectStatement) content.ast()).pagination().orElseThrow() instanceof OffsetLimit);

    assertEquals(
        "SELECT COUNT(*) FROM \"shelter\".\"pet\" WHERE \"pet\".\"id\" >= ?", count.plan().sql());
    assertEquals(List.of(10L), arguments(count));
    assertTrue(count.ast() instanceof CountAst);
  }

  @Test
  void compilesNullableLexicographicKeysetWithTypedRepeatedBindings() {
    CompilerFixture fixture = compilerFixture();
    QueryCompilation<Pet> query =
        compileEntity(
            fixture,
            null,
            List.of(TABLE.nickname().desc().nullsLast(), TABLE.id().desc()),
            false,
            new QueryPagination.Keyset(21, List.of("Mimi", 9L)));

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\", \"pet\".\"nickname\" "
            + "FROM \"shelter\".\"pet\" WHERE "
            + "(\"pet\".\"nickname\" < ? OR \"pet\".\"nickname\" IS NULL) OR "
            + "(\"pet\".\"nickname\" = ? AND \"pet\".\"id\" < ?) "
            + "ORDER BY \"pet\".\"nickname\" DESC NULLS LAST, \"pet\".\"id\" DESC "
            + "LIMIT ?",
        query.plan().sql());
    assertEquals(List.of("Mimi", 9L, 21), arguments(query));
    assertEquals(4, query.plan().parameterCount());
    assertTrue(((SelectStatement) query.ast()).pagination().orElseThrow() instanceof KeysetSeek);
  }

  @Test
  void addsHiddenOrderingSelectionsWithoutChangingTheUserProjectionDecoder() {
    CompilerFixture fixture = compilerFixture();
    QueryCompilation<OrderedRow<Long>> query =
        compileOrderedProjection(
            fixture,
            Projection.scalar(TABLE.id()),
            List.of(TABLE.nickname().asc().nullsFirst(), TABLE.id().asc()),
            false,
            new QueryPagination.LimitOnly(11));

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"nickname\" AS \"__skis_order_0\" "
            + "FROM \"shelter\".\"pet\" ORDER BY "
            + "\"pet\".\"nickname\" ASC NULLS FIRST, \"pet\".\"id\" ASC LIMIT ?",
        query.plan().sql());
    SelectStatement statement = (SelectStatement) query.ast();
    assertEquals(1, statement.hiddenSelections().size());
    assertTrue(statement.hiddenSelections().getFirst() instanceof HiddenSelection);
    assertEquals(List.of(11), arguments(query));
  }

  @Test
  void autoCountUsesSingleDistinctExpressionAndRejectsUnsafeTuples() {
    CompilerFixture fixture = compilerFixture();
    QueryPlanCompiler compiler = fixture.compiler();
    QueryCompilation<Long> count =
        compiler.compileCount(
            fixture.model(),
            TABLE,
            SelectedResult.requiredScalar(
                TABLE, fixture.plans(), Projection.scalar(TABLE.name())),
            List.of(),
            null,
            true);

    assertEquals(
        "SELECT COUNT(DISTINCT \"pet\".\"pet_name\") FROM \"shelter\".\"pet\"", count.plan().sql());
    QueryCompilation<Long> nullableCount =
        compiler.compileCount(
            fixture.model(),
            TABLE,
            SelectedResult.nullableScalar(
                TABLE, fixture.plans(), Projection.nullableScalar(TABLE.nickname())),
            List.of(),
            null,
            true);
    assertEquals(
        "SELECT COUNT(DISTINCT \"pet\".\"nickname\") "
            + "+ CASE WHEN COUNT(*) > COUNT(\"pet\".\"nickname\") THEN 1 ELSE 0 END "
            + "FROM \"shelter\".\"pet\"",
        nullableCount.plan().sql());
    QueryCompilation<Long> entityCount =
        compiler.compileCount(
            fixture.model(),
            TABLE,
            SelectedResult.entity(TABLE, fixture.plans()),
            List.of(),
            null,
            true);
    assertEquals("SELECT COUNT(*) FROM \"shelter\".\"pet\"", entityCount.plan().sql());
    Projection<Pet, Object> unsafeTuple =
        Projection.generated(
            Object.class,
            PET,
            Projection.mapping(QueryPaginationCompilationTest.class),
            List.of(ID, NAME),
            readers -> (resultSet, context) -> new Object());
    assertThrows(
        QueryValidationException.class,
        () ->
            compiler.compileCount(
                fixture.model(),
                TABLE,
                SelectedResult.projection(TABLE, fixture.plans(), unsafeTuple),
                List.of(),
                null,
                true));
  }

  @Test
  void constructsAnIndependentExplicitCountThroughThePublicQueryApi() {
    QueryOperations operations = operations();
    CountQuery explicitCount =
        operations.selectFrom(TABLE).where(TABLE.name().eq("Mimi")).countQuery();

    assertTrue(explicitCount instanceof DefaultCountQuery);
    QueryCompilation<Long> compilation = ((DefaultCountQuery) explicitCount).compilation();
    assertEquals(
        "SELECT COUNT(*) FROM \"shelter\".\"pet\" WHERE \"pet\".\"pet_name\" = ?",
        compilation.plan().sql());
    assertEquals(List.of("Mimi"), arguments(compilation));
  }

  @Test
  void oneImmutableQueryReusesPlansWhileKeepingPageValuesOutsideThem() {
    QueryOperations operations = operations();
    @SuppressWarnings("unchecked")
    DefaultSelectQuery<Pet, Pet> query =
        (DefaultSelectQuery<Pet, Pet>)
            operations.selectFrom(TABLE).where(TABLE.name().eq("Mimi")).orderBy(TABLE.id().asc());

    QueryCompilation<Pet> first = query.compilation(new QueryPagination.Offset(20, 0));
    QueryCompilation<Pet> third = query.compilation(new QueryPagination.Offset(20, 40));
    QueryCompilation<Pet> keysetNine =
        query.compilation(new QueryPagination.Keyset(21, List.of(9L)));
    QueryCompilation<Pet> keysetThree =
        query.compilation(new QueryPagination.Keyset(21, List.of(3L)));

    assertTrue(first.plan() == third.plan());
    assertEquals(first.ast(), third.ast());
    assertEquals(List.of("Mimi", 20, 0L), arguments(first));
    assertEquals(List.of("Mimi", 20, 40L), arguments(third));
    assertTrue(keysetNine.plan() == keysetThree.plan());
    assertEquals(keysetNine.ast(), keysetThree.ast());
    assertEquals(List.of("Mimi", 9L, 21), arguments(keysetNine));
    assertEquals(List.of("Mimi", 3L, 21), arguments(keysetThree));
  }

  @Test
  void validatesStableDistinctNullableKeysetAndContinuationContractsBeforeJdbc() {
    QueryOperations operations = operations();

    SelectQuery<Pet, Pet> unstable = operations.selectFrom(TABLE).orderBy(TABLE.name().asc());
    assertThrows(
        QueryValidationException.class, () -> unstable.fetchSlice(SliceRequest.offset(0, 10)));

    SelectQuery<Pet, Pet> nullableDefault =
        operations.selectFrom(TABLE).orderBy(TABLE.nickname().asc(), TABLE.id().asc());
    assertThrows(
        QueryValidationException.class,
        () -> nullableDefault.fetchSlice(SliceRequest.keysetFirst(10)));

    SelectQuery<Pet, String> stableDistinct =
        operations.select(TABLE.name()).from(TABLE).distinct().orderBy(TABLE.name().asc());
    AssertionError reachedJdbc =
        assertThrows(
            AssertionError.class, () -> stableDistinct.fetchSlice(SliceRequest.keysetFirst(10)));
    assertEquals("plan execution must not occur in this test", reachedJdbc.getMessage());

    SelectQuery<Pet, Pet> ordered = operations.selectFrom(TABLE).orderBy(TABLE.id().asc());
    SliceContinuation foreign =
        SliceContinuation.offset("foreign-query", "foreign-order", 10, "foreign-parameters");
    assertThrows(
        QueryValidationException.class, () -> ordered.fetchSlice(SliceRequest.resume(foreign, 10)));
  }

  @Test
  void unifiedQueryKeepsTheExistingUnpaginatedFastPathPlan() {
    QueryPlanCatalog catalog =
        QueryRuntime.compile(EntityRuntimeRegistry.of(List.of(model())), TestDialect.INSTANCE);
    QueryOperations operations = bind(catalog);
    QueryPredicate<Pet> predicate = TABLE.id().eq(7L);
    @SuppressWarnings("unchecked")
    DefaultSelectQuery<Pet, Pet> query =
        (DefaultSelectQuery<Pet, Pet>) operations.selectFrom(TABLE).where(predicate);

    QueryCompilation<Pet> compilation = query.compilation(QueryPagination.None.INSTANCE);

    assertTrue(compilation.plan() == catalog.require(PET).selectPlan(TABLE, predicate));
  }

  private static List<Object> arguments(QueryCompilation<?> compilation) {
    return ((QueryArguments) compilation.argument()).values();
  }

  private static QueryCompilation<Pet> compileEntity(
      CompilerFixture fixture,
      @Nullable QueryPredicate<Pet> predicate,
      List<SortSpecification<Pet>> orderBy,
      boolean distinct,
      QueryPagination pagination) {
    return fixture.compiler().compileSelection(
        fixture.model(),
        TABLE,
        SelectedResult.entity(TABLE, fixture.plans()),
        List.of(),
        predicate,
        orderBy,
        distinct,
        pagination,
        List.of());
  }

  private static <R> QueryCompilation<OrderedRow<R>> compileOrderedProjection(
      CompilerFixture fixture,
      Projection<Pet, R> projection,
      List<SortSpecification<Pet>> orderBy,
      boolean distinct,
      QueryPagination pagination) {
    return fixture.compiler().compileOrdered(
        fixture.model(),
        TABLE,
        SelectedResult.requiredScalar(TABLE, fixture.plans(), projection),
        List.of(),
        null,
        orderBy,
        distinct,
        pagination);
  }

  private static CompilerFixture compilerFixture() {
    EntityRuntimeModel<Pet> model = model();
    QueryPlanCatalog catalog =
        QueryRuntime.compile(EntityRuntimeRegistry.of(List.of(model)), TestDialect.INSTANCE);
    EntityPlanSet<Pet> plans = catalog.require(PET);
    return new CompilerFixture(model, plans.compiler(), plans);
  }

  private static QueryOperations operations() {
    QueryPlanCatalog catalog =
        QueryRuntime.compile(EntityRuntimeRegistry.of(List.of(model())), TestDialect.INSTANCE);
    return bind(catalog);
  }

  private static QueryOperations bind(QueryPlanCatalog catalog) {
    return catalog.bind(
        new JdbcExecutor(
            new ConnectionProvider() {
              @Override
              public Connection acquire(ExecutionContext context) {
                throw new AssertionError("plan execution must not occur in this test");
              }

              @Override
              public void release(Connection connection, ExecutionContext context) {}
            }));
  }

  private static EntityRuntimeModel<Pet> model() {
    return new EntityRuntimeModel<>(
        PET,
        layout -> (resultSet, context) -> new Pet(1L, "unused", null),
        List.of(
            new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(NAME, JdbcCodecs.STRING),
            new PropertyRuntime<>(NICKNAME, JdbcCodecs.STRING)));
  }

  private record Pet(Long id, String name, String nickname) {}

  private record CompilerFixture(
      EntityRuntimeModel<Pet> model, QueryPlanCompiler compiler, EntityPlanSet<Pet> plans) {}

  private static final class PetTable extends QueryTable<Pet> {

    private final NonNullQueryColumn<Pet, Long> id = nonNullQueryColumn(ID);
    private final NonNullQueryColumn<Pet, String> name = nonNullQueryColumn(NAME);
    private final NullableQueryColumn<Pet, String> nickname = nullableQueryColumn(NICKNAME);

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

    private NullableQueryColumn<Pet, String> nickname() {
      return nickname;
    }

    @Override
    public PetTable as(String alias) {
      return new PetTable(Identifier.of(alias));
    }

    @Override
    public PetTable as(Identifier alias) {
      return new PetTable(alias);
    }
  }

  private enum TestDialect implements Dialect {
    INSTANCE;

    private final DialectCapabilities capabilities =
        DialectCapabilities.of(
            DialectFeature.SCHEMA_QUALIFIED_TABLES,
            DialectFeature.PARAMETERIZED_LIMIT,
            DialectFeature.PARAMETERIZED_OFFSET,
            DialectFeature.NULLS_FIRST_LAST,
            DialectFeature.COUNT_DISTINCT);
    private final SqlRenderer renderer =
        new StandardSqlRenderer(id(), identifierRules(), capabilities);

    @Override
    public String id() {
      return "pagination-test";
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
      return renderer;
    }
  }
}
