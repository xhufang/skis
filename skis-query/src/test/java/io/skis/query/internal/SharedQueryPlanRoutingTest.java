package io.skis.query.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.CompilationCompletedEvent;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionOutcome;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.StatementPurpose;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.DialectLowering;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlLowering;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.DerivedRelation;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.QueryCondition;
import io.skis.query.QueryOperations;
import io.skis.query.QueryParameter;
import io.skis.query.QueryPlanCacheStatistics;
import io.skis.query.QueryTable;
import io.skis.query.SelectQuery;
import io.skis.query.Selectable;
import io.skis.query.SortSpecification;
import io.skis.query.Sql;
import io.skis.query.spi.ProjectionMapping;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.StatementAst;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SharedQueryPlanRoutingTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("name", true));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "", "pet"),
          List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);
  private static final PropertyMeta<Owner, Long> OWNER_ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final EntityMeta<Owner> OWNER =
      EntityMeta.simple(
          Owner.class,
          new TableMeta("", "", "owner"),
          List.of(OWNER_ID),
          new PrimaryKeyMeta<>(List.of(OWNER_ID)),
          false);
  private static final EntityRuntimeRegistry REGISTRY =
      EntityRuntimeRegistry.of(
          List.of(
              new EntityRuntimeModel<>(
                  PET,
                  layout -> (rs, context) -> new Pet(rs.getLong(1), rs.getString(2)),
                  List.of(
                      new PropertyRuntime<>(ID, JdbcCodecs.LONG),
                      new PropertyRuntime<>(NAME, JdbcCodecs.STRING))),
              new EntityRuntimeModel<>(
                  OWNER,
                  layout -> (rs, context) -> new Owner(rs.getLong(1)),
                  List.of(new PropertyRuntime<>(OWNER_ID, JdbcCodecs.LONG)))));

  @Test
  void freshQueriesSharePlansButKeepTheirOwnAstAndArgumentsAndL0SkipsL1() throws Exception {
    Fixture fixture = fixture(16, true);
    DefaultSelectQuery<Long> first = scalar(fixture, 1L);
    QueryCompilation<Long> a = first.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> local = first.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> b = scalar(fixture, 2L).compilation(QueryPagination.None.INSTANCE);

    assertSame(a.plan(), local.plan());
    assertSame(a.ast(), local.ast());
    assertSame(a.plan(), b.plan());
    assertNotSame(a.ast(), b.ast());
    assertNotSame(
        ((SelectStatement) a.ast()).fromClause(), ((SelectStatement) b.ast()).fromClause());
    assertEquals(List.of(1L), bindings(a));
    assertEquals(List.of(2L), bindings(b));
    assertEquals(new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 16), fixture.stats());
    assertEquals(1, fixture.dialect().renders.get());
    assertEquals(1, fixture.dialect().validations.get());
    assertEquals(0, fixture.dialect().legacyRenders.get());
    assertEquals(0, fixture.dialect().legacyValidations.get());
  }

  @Test
  void nestedAndDerivedQueriesUseOnlyResolvedCompilationStages() {
    for (int shape = 0; shape < 6; shape++) {
      Fixture fixture = fixture(16, true);

      compile(nested(fixture, shape, 100L + shape));

      assertEquals(1, fixture.dialect().validations.get(), "shape " + shape);
      assertEquals(1, fixture.dialect().renders.get(), "shape " + shape);
      assertEquals(0, fixture.dialect().legacyValidations.get(), "shape " + shape);
      assertEquals(0, fixture.dialect().legacyRenders.get(), "shape " + shape);
    }
  }

  @Test
  void outerJoinPaginationVariantsKeepIndependentResolvedContextsAndGoldenSql() {
    Fixture fixture = fixture(16, true);
    PetTable pet = new PetTable().as("p");
    OwnerTable owner = new OwnerTable().as("o");
    DefaultSelectQuery<Long> query =
        (DefaultSelectQuery<Long>)
            fixture
                .operations()
                .select(pet.id)
                .from(pet)
                .leftJoin(owner)
                .on(pet.id.eq(owner.id))
                .orderBy(pet.id.asc());

    QueryCompilation<Long> content = query.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> offset = query.compilation(new QueryPagination.Offset(10, 20));
    QueryCompilation<Long> keyset = query.compilation(new QueryPagination.Keyset(10, List.of(7L)));

    assertEquals(
        "SELECT \"p\".\"id\" FROM \"pet\" AS \"p\" LEFT JOIN \"owner\" AS \"o\" "
            + "ON \"p\".\"id\" = \"o\".\"id\" ORDER BY \"p\".\"id\" ASC",
        content.plan().sql());
    assertEquals(content.plan().sql() + " LIMIT ? OFFSET ?", offset.plan().sql());
    assertEquals(
        "SELECT \"p\".\"id\" FROM \"pet\" AS \"p\" LEFT JOIN \"owner\" AS \"o\" "
            + "ON \"p\".\"id\" = \"o\".\"id\" WHERE \"p\".\"id\" > ? "
            + "ORDER BY \"p\".\"id\" ASC LIMIT ?",
        keyset.plan().sql());
    assertEquals(3, fixture.dialect().validations.get());
    assertEquals(3, fixture.dialect().renders.get());
    assertEquals(0, fixture.dialect().legacyValidations.get());
    assertEquals(0, fixture.dialect().legacyRenders.get());
  }

  @Test
  void reusedRuntimeScopeRebasesOuterJoinNullExtension() {
    PetTable pet = new PetTable().as("p");
    OwnerTable owner = new OwnerTable().as("o");
    QueryCondition on = pet.id.eq(owner.id);
    CompiledQueryStructure innerStructure =
        QueryTestSupport.compile(
            pet, List.of(new QueryJoin(JoinType.INNER, QueryRelations.of(owner), on)), null);
    CompiledQueryStructure leftStructure =
        QueryTestSupport.compile(
            pet, List.of(new QueryJoin(JoinType.LEFT, QueryRelations.of(owner), on)), null);

    TableRuntimeScope innerScope = TableRuntimeScope.resolve(REGISTRY, innerStructure.fromClause());
    TableRuntimeScope leftScope = innerScope.forFromClause(leftStructure.fromClause());

    assertSame(innerScope.require(owner).model(), leftScope.require(owner).model());
    assertEquals(
        Nullability.NON_NULL,
        innerScope.effectiveNullability(QueryTableModel.of(owner).internalColumn(OWNER_ID)));
    assertEquals(
        Nullability.NULLABLE,
        leftScope.effectiveNullability(QueryTableModel.of(owner).internalColumn(OWNER_ID)));
  }

  @Test
  void runtimeScopeFallsBackForChangedEntityIdentityOrdinalAndSourceCount() {
    PetTable pet = new PetTable().as("p");
    OwnerTable owner = new OwnerTable().as("o");
    CompiledQueryStructure original =
        QueryTestSupport.compile(
            pet, List.of(new QueryJoin(JoinType.CROSS, QueryRelations.of(owner), null)), null);
    TableRuntimeScope scope = TableRuntimeScope.resolve(REGISTRY, original.fromClause());

    PetTable replacementPet = new PetTable().as("p");
    CompiledQueryStructure changedIdentity =
        QueryTestSupport.compile(
            replacementPet,
            List.of(new QueryJoin(JoinType.CROSS, QueryRelations.of(owner), null)),
            null);
    TableRuntimeScope identityScope = scope.forFromClause(changedIdentity.fromClause());
    assertEquals(0, identityScope.require(replacementPet).occurrenceOrdinal());
    assertThrows(QueryValidationException.class, () -> identityScope.require(pet));

    CompiledQueryStructure changedOrdinals =
        QueryTestSupport.compile(
            owner, List.of(new QueryJoin(JoinType.CROSS, QueryRelations.of(pet), null)), null);
    TableRuntimeScope ordinalScope = scope.forFromClause(changedOrdinals.fromClause());
    assertEquals(0, ordinalScope.require(owner).occurrenceOrdinal());
    assertEquals(1, ordinalScope.require(pet).occurrenceOrdinal());

    CompiledQueryStructure rootOnly = QueryTestSupport.compile(pet, List.of(), null);
    TableRuntimeScope contracted = scope.forFromClause(rootOnly.fromClause());
    assertEquals(0, contracted.require(pet).occurrenceOrdinal());
    assertThrows(QueryValidationException.class, () -> contracted.require(owner));
  }

  @Test
  void runtimeScopeFallsBackForChangedDerivedRelationReference() {
    OwnerTable inner = new OwnerTable().as("inner_owner");
    var output = Sql.output(inner.id, "id");
    DerivedRelation original =
        Sql.derived(Sql.select(inner.id).from(inner), "derived_owner", output);
    DerivedRelation replacement = original.as("derived_owner");
    CompiledQueryStructure originalStructure =
        SelectQueryState.create(SelectedResult.requiredScalar(original.column(output)), original)
            .structure();
    CompiledQueryStructure replacementStructure =
        SelectQueryState.create(
                SelectedResult.requiredScalar(replacement.column(output)), replacement)
            .structure();

    TableRuntimeScope scope = TableRuntimeScope.resolve(REGISTRY, originalStructure.fromClause());
    TableRuntimeScope replacementScope = scope.forFromClause(replacementStructure.fromClause());

    assertEquals(0, replacementScope.require(replacement));
    assertThrows(QueryValidationException.class, () -> replacementScope.require(original));
  }

  @Test
  void rewrittenMembershipValidatesOriginalAndFinalStructuresWithoutLegacyReanalysis() {
    Fixture fixture = fixture(16, true);
    PetTable pet = new PetTable().as("p");
    DefaultSelectQuery<Long> query =
        (DefaultSelectQuery<Long>)
            fixture.operations().select(pet.id).from(pet).where(pet.id.in(List.of()));

    QueryCompilation<Long> compilation = query.compilation(QueryPagination.None.INSTANCE);

    assertEquals("SELECT \"p\".\"id\" FROM \"pet\" AS \"p\" WHERE FALSE", compilation.plan().sql());
    assertEquals(2, fixture.dialect().validations.get());
    assertEquals(1, fixture.dialect().renders.get());
    assertEquals(0, fixture.dialect().legacyValidations.get());
    assertEquals(0, fixture.dialect().legacyRenders.get());
  }

  @Test
  void aliasedAndComplexEntityFallbacksEnterL1WhileFixedFastSlotsRemainIndependent() {
    Fixture fixture = fixture(16, true);
    for (int value = 1; value <= 2; value++) {
      PetTable table = new PetTable();
      compile(fixture.operations().selectFrom(table).where(table.id.eq((long) value)));
      compile(fixture.operations().selectFrom(table));
    }
    assertEquals(0, fixture.stats().size());
    for (int value = 1; value <= 2; value++) {
      PetTable alias = new PetTable().as("p");
      compile(fixture.operations().selectFrom(alias));
      compile(fixture.operations().selectFrom(alias).where(alias.id.eq((long) value)));
      PetTable table = new PetTable();
      compile(fixture.operations().selectFrom(table).where(table.id.ge((long) value)));
    }
    assertEquals(new QueryPlanCacheStatistics(3, 3, 0, 0, 3, 16), fixture.stats());
  }

  @Test
  void paginationValuesShareButModesAndKeysetNullShapesStaySeparate() throws Exception {
    Fixture fixture = fixture(16, true);
    PetTable first = new PetTable();
    PetTable second = new PetTable();
    DefaultSelectQuery<Long> a = orderedScalar(fixture, first, 1L);
    DefaultSelectQuery<Long> b = orderedScalar(fixture, second, 2L);
    List<QueryPagination> firstPages =
        List.of(
            QueryPagination.None.INSTANCE,
            new QueryPagination.LimitOnly(3),
            new QueryPagination.Offset(3, 6),
            new QueryPagination.Keyset(3, List.of("Ada", 7L)),
            new QueryPagination.Keyset(3, Arrays.asList(null, 7L)));
    List<QueryPagination> secondPages =
        List.of(
            QueryPagination.None.INSTANCE,
            new QueryPagination.LimitOnly(9),
            new QueryPagination.Offset(9, 18),
            new QueryPagination.Keyset(9, List.of("Bea", 8L)),
            new QueryPagination.Keyset(9, Arrays.asList(null, 8L)));
    List<Object> plans = new ArrayList<>();
    for (int index = 0; index < firstPages.size(); index++) {
      QueryCompilation<Long> left = a.compilation(firstPages.get(index));
      QueryCompilation<Long> right = b.compilation(secondPages.get(index));
      assertSame(left.plan(), right.plan());
      for (Object previous : plans) {
        assertNotSame(previous, left.plan());
      }
      plans.add(left.plan());
      assertEquals(2L, bindings(right).getFirst());
    }
    // Returning to a previous shape misses the single recent-plan slot and reuses shared L1.
    assertSame(plans.getFirst(), a.compilation(QueryPagination.None.INSTANCE).plan());
    assertEquals(new QueryPlanCacheStatistics(6, 5, 0, 0, 5, 16), fixture.stats());
    assertEquals(List.of(2L, 8L, 9), bindings(b.compilation(secondPages.getLast())));
  }

  @Test
  void oneQueryPublishesMatchingPaginationPlansWhenShapesCompleteInReverseOrder() throws Exception {
    List<QueryPagination> pages =
        List.of(
            new QueryPagination.Offset(3, 6),
            new QueryPagination.Keyset(4, List.of("Ada", 7L)),
            new QueryPagination.Keyset(5, Arrays.asList(null, 8L)));
    List<QueryPagination> changedValues =
        List.of(
            new QueryPagination.Offset(9, 18),
            new QueryPagination.Keyset(10, List.of("Bea", 17L)),
            new QueryPagination.Keyset(11, Arrays.asList(null, 18L)));
    List<List<Object>> expectedBindings =
        List.of(List.of(1L, 3, 6L), List.of(1L, "Ada", "Ada", 7L, 4), List.of(1L, 8L, 5));
    List<List<Object>> changedBindings =
        List.of(List.of(1L, 9, 18L), List.of(1L, "Bea", "Bea", 17L, 10), List.of(1L, 18L, 11));
    Fixture baseline = fixture(0, true);
    List<QueryCompilation<Long>> expected =
        pages.stream()
            .map(page -> orderedScalar(baseline, new PetTable(), 1L).compilation(page))
            .toList();

    // Stable L1, disabled L1 and an unstable dialect all use the same query-local publication path.
    for (int mode = 0; mode < 3; mode++) {
      Fixture fixture = fixture(mode == 1 ? 0 : 16, mode != 2);
      DefaultSelectQuery<Long> query = orderedScalar(fixture, new PetTable(), 1L);
      RenderSequence sequence = new RenderSequence();
      fixture.dialect().sequence = sequence;
      List<Future<QueryCompilation<Long>>> futures = new ArrayList<>();
      try (var workers = Executors.newFixedThreadPool(3)) {
        try {
          for (int index = 0; index < pages.size(); index++) {
            QueryPagination page = pages.get(index);
            futures.add(workers.submit(() -> query.compilation(page)));
            assertTrue(sequence.entered.get(index).await(5, TimeUnit.SECONDS));
          }
          for (int index = pages.size() - 1; index >= 0; index--) {
            sequence.release.get(index).countDown();
            QueryCompilation<Long> completed = futures.get(index).get(5, TimeUnit.SECONDS);
            assertPaginationCompilation(
                expected.get(index), completed, expectedBindings.get(index));
            QueryCompilation<Long> local = query.compilation(changedValues.get(index));
            assertSame(completed.plan(), local.plan());
            assertPaginationCompilation(expected.get(index), local, changedBindings.get(index));
          }
        } finally {
          sequence.release.forEach(CountDownLatch::countDown);
          fixture.dialect().sequence = null;
        }
      }
      // Offset finished last. Revisiting every other shape must not reuse that shape's SQL/Binder.
      for (int index = pages.size() - 1; index >= 0; index--) {
        QueryCompilation<Long> revisited = query.compilation(changedValues.get(index));
        assertPaginationCompilation(expected.get(index), revisited, changedBindings.get(index));
        if (mode == 0) {
          assertSame(futures.get(index).get(5, TimeUnit.SECONDS).plan(), revisited.plan());
        }
      }
      assertEquals(
          mode == 0
              ? new QueryPlanCacheStatistics(3, 3, 0, 0, 3, 16)
              : new QueryPlanCacheStatistics(0, 0, 0, 0, 0, mode == 1 ? 0 : 16),
          fixture.stats());
    }
  }

  private static void assertPaginationCompilation(
      QueryCompilation<Long> expected, QueryCompilation<Long> actual, List<Object> values)
      throws Exception {
    assertEquals(expected.plan().sql(), actual.plan().sql());
    assertEquals(
        expected.plan().renderedSql().parameters(), actual.plan().renderedSql().parameters());
    assertEquals(values, bindings(actual));
  }

  @Test
  void orderedDecoderAndCountNeverReuseContentEvenWhenSqlMatches() {
    Fixture fixture = fixture(16, true);
    PetTable table = new PetTable();
    SelectedResult<Long> selected = SelectedResult.requiredScalar(table.id);
    CompiledQueryStructure structure = QueryTestSupport.compile(selected, table, List.of(), null);
    List<SortSpecification> order = List.of(table.id.asc());
    QueryPagination firstPage = new QueryPagination.LimitOnly(3);
    QueryPagination laterPage = new QueryPagination.LimitOnly(9);
    QueryCompilation<Long> content =
        fixture
            .catalog()
            .compiler()
            .compileSelection(
                selected,
                structure,
                order,
                false,
                firstPage,
                List.of(),
                QueryTestSupport.arguments(structure).withPagination(firstPage),
                null,
                StatementPurpose.PRIMARY);
    QueryCompilation<OrderedRow<Long>> ordered =
        fixture
            .catalog()
            .compiler()
            .compileOrdered(
                selected,
                structure,
                order,
                false,
                firstPage,
                QueryTestSupport.arguments(structure).withPagination(firstPage),
                null,
                StatementPurpose.PRIMARY,
                null);
    QueryCompilation<OrderedRow<Long>> repeated =
        fixture
            .catalog()
            .compiler()
            .compileOrdered(
                selected,
                structure,
                order,
                false,
                laterPage,
                QueryTestSupport.arguments(structure).withPagination(laterPage),
                null,
                StatementPurpose.PRIMARY,
                null);
    QueryCompilation<Long> count =
        fixture
            .catalog()
            .compiler()
            .compileCount(
                selected,
                structure,
                false,
                QueryTestSupport.arguments(structure),
                null,
                StatementPurpose.PAGE_COUNT,
                null);
    assertEquals(content.plan().sql(), ordered.plan().sql());
    assertNotSame(content.plan(), ordered.plan());
    assertSame(ordered.plan(), repeated.plan());
    assertNotSame(content.plan(), count.plan());
    assertEquals(new QueryPlanCacheStatistics(1, 3, 0, 0, 3, 16), fixture.stats());
  }

  @Test
  void nullableDistinctCountBindsEveryFinalPositionOfAParameterizedExpression() throws Exception {
    Fixture fixture = fixture(16, true);
    PetTable outer = new PetTable().as("p");
    OwnerTable owner = new OwnerTable().as("o");
    QueryParameter<Long> parameter = Sql.parameter(Long.class);
    var lookup = Sql.select(owner.id).from(owner).where(owner.id.ge(parameter));
    DefaultSelectQuery<Long> query =
        (DefaultSelectQuery<Long>)
            fixture
                .operations()
                .query(Sql.select(Sql.scalar(lookup)).from(outer))
                .bind(parameter, 7L);

    QueryCompilation<Long> count = query.distinct().countCompilation();

    assertEquals(
        "SELECT COUNT(DISTINCT (SELECT \"o\".\"id\" FROM \"owner\" AS \"o\" "
            + "WHERE \"o\".\"id\" >= ?)) + CASE WHEN COUNT(*) > "
            + "COUNT((SELECT \"o\".\"id\" FROM \"owner\" AS \"o\" "
            + "WHERE \"o\".\"id\" >= ?)) THEN 1 ELSE 0 END FROM \"pet\" AS \"p\"",
        count.plan().sql());
    assertEquals(List.of(7L, 7L), bindings(count));
  }

  @Test
  void countVariantsShareTheirSingleResolvedAnalysisAcrossValidationAndRendering()
      throws Exception {
    List<String> expectedSql =
        List.of(
            "SELECT COUNT(*) FROM \"pet\" AS \"p\" WHERE \"p\".\"id\" >= ?",
            "SELECT COUNT(DISTINCT \"p\".\"id\") FROM \"pet\" AS \"p\" "
                + "WHERE \"p\".\"id\" >= ?",
            "SELECT COUNT(*) FROM \"pet\" AS \"p\"",
            "SELECT COUNT(*) FROM \"pet\" AS \"p\" WHERE FALSE");
    List<List<?>> expectedBindings = List.of(List.of(7L), List.of(7L), List.of(), List.of());
    for (int shape = 0; shape < 4; shape++) {
      Fixture fixture = fixture(16, true);
      PetTable table = new PetTable().as("p");
      DefaultSelectQuery<Long> query =
          switch (shape) {
            case 0 -> scalar(fixture, table, 7L);
            case 1 -> scalar(fixture, table, 7L).distinct();
            case 2 -> nested(fixture, 3, 7L);
            case 3 ->
                (DefaultSelectQuery<Long>)
                    fixture.operations().select(table.id).from(table).where(table.id.in(List.of()));
            default -> throw new AssertionError(shape);
          };

      QueryCompilation<Long> count = query.countCompilation();

      assertTrue(count.ast() instanceof CountAst, "shape " + shape);
      fixture.dialect().assertAnalysisHandoff(count.ast());
      assertEquals(2, fixture.dialect().validations.get(), "shape " + shape);
      assertEquals(1, fixture.dialect().renders.get(), "shape " + shape);
      assertEquals(0, fixture.dialect().legacyValidations.get(), "shape " + shape);
      assertEquals(0, fixture.dialect().legacyRenders.get(), "shape " + shape);
      assertEquals(expectedSql.get(shape), count.plan().sql(), "shape " + shape);
      assertEquals(expectedBindings.get(shape), bindings(count), "shape " + shape);
    }
  }

  @Test
  void resultNullabilityProjectionContractAndCatalogIdentityStaySeparate() {
    Fixture fixture = fixture(16, true);
    PetTable table = new PetTable();
    QueryCompilation<Long> required =
        compileSelected(fixture, table, SelectedResult.requiredScalar(table.id));
    QueryCompilation<Long> nullable =
        compileSelected(fixture, table, SelectedResult.nullableScalar(table.id));
    QueryCompilation<IdView> projected =
        compileSelected(fixture, table, SelectedResult.projection(projection(table)));
    PetTable rebuilt = new PetTable();
    QueryCompilation<IdView> repeated =
        compileSelected(fixture, rebuilt, SelectedResult.projection(projection(rebuilt)));
    assertNotSame(required.plan(), nullable.plan());
    assertNotSame(required.plan(), projected.plan());
    assertSame(projected.plan(), repeated.plan());
    Fixture otherCatalog = fixture(16, true);
    assertNotSame(
        required.plan(),
        compileSelected(otherCatalog, table, SelectedResult.requiredScalar(table.id)).plan());
    assertEquals(new QueryPlanCacheStatistics(1, 3, 0, 0, 3, 16), fixture.stats());
  }

  @Test
  void nestedEntityInvalidationRemovesContentAndCountButPreservesRootOnlyPlans() throws Exception {
    for (int shape = 0; shape < 6; shape++) {
      Fixture fixture = fixture(32, true);
      QueryCompilation<Long> rootOnly =
          scalar(fixture, 1L).compilation(QueryPagination.None.INSTANCE);
      DefaultSelectQuery<Long> first = nested(fixture, shape, 11L);
      DefaultSelectQuery<Long> second = nested(fixture, shape, 22L);
      QueryCompilation<Long> a = first.compilation(QueryPagination.None.INSTANCE);
      QueryCompilation<Long> b = second.compilation(QueryPagination.None.INSTANCE);
      assertSame(a.plan(), b.plan());
      assertNotSame(a.ast(), b.ast());
      assertEquals(List.of(11L), bindings(a));
      assertEquals(List.of(22L), bindings(b));
      QueryCompilation<Long> countA = first.countCompilation();
      QueryCompilation<Long> countB = second.countCompilation();
      assertSame(countA.plan(), countB.plan());
      assertEquals(shape == 3 ? List.of() : List.of(22L), bindings(countB));
      if (shape == 3) {
        // Count deliberately retains the original description's dependencies after SELECT pruning.
        assertTrue(a.plan().sql().contains("\"owner\""));
        assertFalse(countA.plan().sql().contains("\"owner\""));
      }
      assertEquals(new QueryPlanCacheStatistics(2, 3, 0, 0, 3, 32), fixture.stats());
      assertEquals(2, fixture.catalog().invalidateProjectionPlans(OWNER));
      assertEquals(1, fixture.stats().size());
      assertSame(
          rootOnly.plan(), scalar(fixture, 2L).compilation(QueryPagination.None.INSTANCE).plan());
      DefaultSelectQuery<Long> rebuilt = nested(fixture, shape, 33L);
      assertNotSame(a.plan(), rebuilt.compilation(QueryPagination.None.INSTANCE).plan());
      assertNotSame(countA.plan(), rebuilt.countCompilation().plan());
      assertEquals(new QueryPlanCacheStatistics(3, 5, 0, 2, 3, 32), fixture.stats());
    }
  }

  @Test
  void repeatedLogicalSlotsBindOnlyCurrentValues() throws Exception {
    Fixture fixture = fixture(16, true);
    for (long value : List.of(1L, 2L)) {
      PetTable table = new PetTable();
      QueryCondition repeated = table.id.ge(value);
      QueryCompilation<Long> compilation =
          compile(fixture.operations().select(table.id).from(table).where(repeated.and(repeated)));
      assertEquals(List.of(value, value), bindings(compilation));
      assertEquals(
          List.of(0, 0),
          compilation.plan().renderedSql().parameters().stream()
              .map(slot -> slot.ordinal())
              .toList());
    }
    assertEquals(new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 16), fixture.stats());
  }

  @Test
  void membershipLengthDistinctCountAndNullableBinderShapesAreIsolated() throws Exception {
    Fixture fixture = fixture(16, true);
    PetTable table = new PetTable();
    QueryCompilation<Long> two =
        compile(
            fixture.operations().select(table.id).from(table).where(table.id.in(List.of(1L, 2L))));
    QueryCompilation<Long> alsoTwo =
        compile(
            fixture.operations().select(table.id).from(table).where(table.id.in(List.of(3L, 4L))));
    QueryCompilation<Long> three =
        compile(
            fixture
                .operations()
                .select(table.id)
                .from(table)
                .where(table.id.in(List.of(3L, 4L, 5L))));
    assertSame(two.plan(), alsoTwo.plan());
    assertNotSame(two.plan(), three.plan());
    assertEquals(List.of(3L, 4L), bindings(alsoTwo));
    assertEquals(List.of(3L, 4L, 5L), bindings(three));

    QueryParameter<Long> parameter = Sql.parameter(Long.class);
    var description = Sql.select(table.id).from(table).where(table.id.eq(parameter));
    QueryCompilation<Long> nullValue =
        compile(fixture.operations().query(description).bind(parameter, null));
    QueryCompilation<Long> nonNullValue =
        compile(fixture.operations().query(description).bind(parameter, 6L));
    QueryCompilation<Long> required =
        compile(fixture.operations().select(table.id).from(table).where(table.id.eq(6L)));
    assertSame(nullValue.plan(), nonNullValue.plan());
    assertNotSame(nonNullValue.plan(), required.plan());
    assertEquals(java.util.Collections.singletonList(null), bindings(nullValue));
    assertEquals(List.of(6L), bindings(nonNullValue));

    DefaultSelectQuery<Long> plain =
        (DefaultSelectQuery<Long>) fixture.operations().select(table.id).from(table);
    assertNotSame(plain.countCompilation().plan(), plain.distinct().countCompilation().plan());
  }

  @Test
  void disabledAndUnstableDialectBypassL1ButKeepL0AndWorkingL2() throws Exception {
    for (Fixture fixture : List.of(fixture(0, true), fixture(16, false))) {
      DefaultSelectQuery<Long> query = scalar(fixture, 1L);
      QueryCompilation<Long> first = query.compilation(QueryPagination.None.INSTANCE);
      assertSame(first.plan(), query.compilation(QueryPagination.None.INSTANCE).plan());
      QueryCompilation<Long> second =
          scalar(fixture, 2L).compilation(QueryPagination.None.INSTANCE);
      assertNotSame(first.plan(), second.plan());
      assertEquals(List.of(2L), bindings(second));
      assertEquals(0, fixture.stats().hitCount());
      assertEquals(0, fixture.stats().missCount());
      assertEquals(0, fixture.stats().size());
      assertEquals(2, fixture.dialect().renders.get());
    }
  }

  @Test
  void unregisteredNestedDependenciesBypassSharingWithoutRejectingExecutableSql() throws Exception {
    Fixture fixture = fixture(16, true);
    PropertyMeta<ExternalRow, Long> id =
        new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
    EntityMeta<ExternalRow> entity =
        EntityMeta.simple(
            ExternalRow.class,
            new TableMeta("", "", "external_rows"),
            List.of(id),
            new PrimaryKeyMeta<>(List.of(id)),
            false);
    QueryTable<ExternalRow> external = new RuntimeQueryTable<>(entity);
    Object previous = null;
    for (long value : List.of(1L, 2L)) {
      PetTable table = new PetTable();
      DefaultSelectQuery<Long> query =
          (DefaultSelectQuery<Long>)
              fixture
                  .operations()
                  .select(table.id)
                  .from(table)
                  .where(table.id.ge(value).and(Sql.exists(Sql.selectFrom(external))));
      QueryCompilation<Long> compiled = query.compilation(QueryPagination.None.INSTANCE);
      assertNotSame(previous, compiled.plan());
      assertSame(compiled.plan(), query.compilation(QueryPagination.None.INSTANCE).plan());
      assertEquals(List.of(value), bindings(compiled));
      assertTrue(compiled.plan().sql().contains("external_rows"));
      previous = compiled.plan();
    }
    assertEquals(new QueryPlanCacheStatistics(0, 0, 0, 0, 0, 16), fixture.stats());
    assertEquals(2, fixture.dialect().renders.get());
  }

  @Test
  void capacityOneClearAndInvalidationPreserveExistingL0ButForceFreshCompilation() {
    Fixture fixture = fixture(1, true);
    DefaultSelectQuery<Long> original = scalar(fixture, 1L);
    QueryCompilation<Long> a = original.compilation(QueryPagination.None.INSTANCE);
    scalar(fixture, 2L).countCompilation();
    QueryCompilation<Long> b = scalar(fixture, 3L).compilation(QueryPagination.None.INSTANCE);
    assertNotSame(a.plan(), b.plan());
    assertEquals(2, fixture.stats().evictionCount());
    fixture.catalog().clearProjectionPlans();
    assertSame(a.plan(), original.compilation(QueryPagination.None.INSTANCE).plan());
    QueryCompilation<Long> c = scalar(fixture, 4L).compilation(QueryPagination.None.INSTANCE);
    assertNotSame(b.plan(), c.plan());
    assertEquals(1, fixture.catalog().invalidateProjectionPlans(PET));
    assertNotSame(c.plan(), scalar(fixture, 5L).compilation(QueryPagination.None.INSTANCE).plan());
    assertEquals(2, fixture.stats().invalidationCount());
    assertEquals(1, fixture.stats().size());
  }

  @Test
  void failedRenderIsNotPublishedAndInvalidQueriesCannotHideBehindAWarmCache() {
    Fixture fixture = fixture(16, true);
    fixture.dialect().failNext = true;
    assertThrows(
        QueryValidationException.class,
        () -> scalar(fixture, 1L).compilation(QueryPagination.None.INSTANCE));
    assertEquals(0, fixture.stats().size());
    scalar(fixture, 2L).compilation(QueryPagination.None.INSTANCE);
    PetTable root = new PetTable();
    PetTable foreignOccurrence = new PetTable();
    assertThrows(
        QueryValidationException.class,
        () ->
            compile(
                fixture
                    .operations()
                    .select(root.id)
                    .from(root)
                    .where(foreignOccurrence.id.ge(3L))));
    assertEquals(new QueryPlanCacheStatistics(0, 2, 0, 0, 1, 16), fixture.stats());
  }

  @Test
  void concurrentFreshQueriesCompileOnceAndBindIndependentValues() throws Exception {
    Fixture fixture = fixture(16, true);
    int callers = 8;
    CountDownLatch start = new CountDownLatch(1);
    fixture.dialect().entered = new CountDownLatch(1);
    fixture.dialect().release = new CountDownLatch(1);
    List<Future<QueryCompilation<Long>>> futures = new ArrayList<>();
    try (var workers = Executors.newFixedThreadPool(callers)) {
      try {
        for (long value = 0; value < callers; value++) {
          long ownValue = value;
          futures.add(
              workers.submit(
                  () -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    QueryCompilation<Long> compiled =
                        scalar(fixture, ownValue).compilation(QueryPagination.None.INSTANCE);
                    assertEquals(List.of(ownValue), bindings(compiled));
                    return compiled;
                  }));
        }
        start.countDown();
        assertTrue(fixture.dialect().entered.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fixture.stats().missCount() < callers && System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
        assertEquals(callers, fixture.stats().missCount());
      } finally {
        fixture.dialect().release.countDown();
      }
      QueryCompilation<Long> first = futures.getFirst().get(5, TimeUnit.SECONDS);
      for (Future<QueryCompilation<Long>> future : futures) {
        assertSame(first.plan(), future.get(5, TimeUnit.SECONDS).plan());
      }
    }
    assertEquals(1, fixture.dialect().renders.get());
    assertEquals(new QueryPlanCacheStatistics(0, callers, 0, 0, 1, 16), fixture.stats());
  }

  @Test
  void reportsDecoderPreparationFailuresForContentOrderedAndCountPlans() {
    for (int variant = 0; variant < 3; variant++) {
      Fixture fixture = fixture(16, true);
      PetTable table = new PetTable();
      IllegalStateException expected = new IllegalStateException("decoder factory failed");
      ProjectionMapping<IdView> mapping =
          ProjectionMapping.generated(
              GeneratedModelAbi.CURRENT,
              IdView.class,
              "failing-id-view",
              List.of(
                  new ProjectionMapping.Parameter(0, "id", Long.class, Nullability.NON_NULL, 0)),
              readers -> {
                throw expected;
              });
      SelectedResult<IdView> selected = SelectedResult.projection(mapping.bind(table.id));
      CompiledQueryStructure structure = QueryTestSupport.compile(selected, table, List.of(), null);
      QueryArguments arguments = QueryTestSupport.arguments(structure);
      List<CompilationCompletedEvent> events = new ArrayList<>();
      StatementPurpose purpose =
          variant == 2 ? StatementPurpose.PAGE_COUNT : StatementPurpose.PRIMARY;
      OperationObservation observation =
          compilationListener(events)
              .startOperation(ExecutionOperationKind.FETCH_PAGE, fixture.dialect().id());
      java.util.Objects.requireNonNull(observation);
      int planVariant = variant;

      assertSame(
          expected,
          assertThrows(
              IllegalStateException.class,
              () -> {
                QueryPlanCompiler compiler = fixture.catalog().compiler();
                switch (planVariant) {
                  case 0 ->
                      compiler.compileSelection(
                          selected,
                          structure,
                          List.of(),
                          false,
                          QueryPagination.None.INSTANCE,
                          List.of(),
                          arguments,
                          observation,
                          purpose);
                  case 1 ->
                      compiler.compileOrdered(
                          selected,
                          structure,
                          List.of(),
                          false,
                          QueryPagination.None.INSTANCE,
                          arguments,
                          observation,
                          purpose,
                          null);
                  case 2 ->
                      compiler.compileCount(
                          selected, structure, false, arguments, observation, purpose, null);
                  default -> throw new AssertionError(planVariant);
                }
              }));

      assertEquals(1, events.size());
      CompilationCompletedEvent event = events.getFirst();
      assertEquals(observation.operationId(), event.operationId());
      assertEquals(purpose, event.purpose());
      assertEquals(ExecutionOutcome.FAILED, event.outcome());
      assertEquals(SkisDiagnosticStage.COMPILATION, event.failureStage());
      assertNull(event.sqlTemplate());
      assertNull(event.sqlFingerprint());
      assertEquals(0, fixture.dialect().renders.get());
      assertEquals(0, fixture.stats().size());
      observation.completeFailed(expected, SkisDiagnosticStage.COMPILATION);
    }
  }

  @Test
  void includesDecoderPreparationInCompilationTimeAndKeepsSharedHitsSilent() {
    for (int mode = 0; mode < 3; mode++) {
      Fixture fixture = fixture(mode == 1 ? 0 : 16, mode != 2);
      PetTable table = new PetTable();
      AtomicLong preparationNanos = new AtomicLong();
      ProjectionMapping<IdView> mapping =
          ProjectionMapping.generated(
              GeneratedModelAbi.CURRENT,
              IdView.class,
              "timed-id-view",
              List.of(
                  new ProjectionMapping.Parameter(0, "id", Long.class, Nullability.NON_NULL, 0)),
              readers -> {
                long started = System.nanoTime();
                ProjectionMapping.ValueReader<Long> id = readers.reader(0, Long.class);
                // A controlled preparation delay checks timing coverage, not a performance target.
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                preparationNanos.addAndGet(System.nanoTime() - started);
                return (rs, context) -> new IdView(id.read(rs, context));
              });
      SelectedResult<IdView> selected = SelectedResult.projection(mapping.bind(table.id));
      List<CompilationCompletedEvent> events = new ArrayList<>();
      ExecutionListenerDispatcher dispatcher = compilationListener(events);
      OperationObservation firstOperation =
          java.util.Objects.requireNonNull(
              dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, fixture.dialect().id()));

      QueryCompilation<IdView> first =
          compileObservedSelection(fixture, table, selected, firstOperation);
      firstOperation.completeSucceeded(ExecutionTermination.COMPLETED);

      assertEquals(1, events.size());
      assertEquals(ExecutionOutcome.SUCCEEDED, events.getFirst().outcome());
      assertTrue(preparationNanos.get() > 0L);
      assertTrue(events.getFirst().durationNanos() >= preparationNanos.get());
      preparationNanos.set(0L);
      OperationObservation secondOperation =
          java.util.Objects.requireNonNull(
              dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, fixture.dialect().id()));
      QueryCompilation<IdView> second =
          compileObservedSelection(fixture, table, selected, secondOperation);
      secondOperation.completeSucceeded(ExecutionTermination.COMPLETED);

      if (mode == 0) {
        assertSame(first.plan(), second.plan());
        assertEquals(1, events.size());
        assertEquals(1, fixture.stats().hitCount());
      } else {
        assertNotSame(first.plan(), second.plan());
        assertEquals(2, events.size());
        assertTrue(events.getLast().durationNanos() >= preparationNanos.get());
      }
    }
  }

  @Test
  void observedRenderFailureIsReportedOnceAndCanBeRetried() {
    Fixture fixture = fixture(16, true);
    PetTable table = new PetTable();
    SelectedResult<Long> selected = SelectedResult.requiredScalar(table.id);
    List<CompilationCompletedEvent> events = new ArrayList<>();
    ExecutionListenerDispatcher dispatcher = compilationListener(events);
    OperationObservation failed =
        java.util.Objects.requireNonNull(
            dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, fixture.dialect().id()));
    fixture.dialect().failNext = true;

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () -> compileObservedSelection(fixture, table, selected, failed));
    failed.completeFailed(failure, SkisDiagnosticStage.COMPILATION);

    assertEquals(1, events.size());
    assertEquals(ExecutionOutcome.FAILED, events.getFirst().outcome());
    assertEquals(0, fixture.stats().size());
    OperationObservation retry =
        java.util.Objects.requireNonNull(
            dispatcher.startOperation(ExecutionOperationKind.FETCH_LIST, fixture.dialect().id()));
    compileObservedSelection(fixture, table, selected, retry);
    retry.completeSucceeded(ExecutionTermination.COMPLETED);
    assertEquals(2, events.size());
    assertEquals(ExecutionOutcome.SUCCEEDED, events.getLast().outcome());
    assertEquals(1, fixture.stats().size());
  }

  @Test
  void observedConcurrentCompilationNotifiesOnlyTheCompilerAndNeverTheWaiter() throws Exception {
    Fixture fixture = fixture(16, true);
    fixture.dialect().entered = new CountDownLatch(1);
    fixture.dialect().release = new CountDownLatch(1);
    List<CompilationCompletedEvent> events = Collections.synchronizedList(new ArrayList<>());
    ExecutionListenerDispatcher dispatcher = compilationListener(events);
    List<Future<QueryCompilation<Long>>> futures = new ArrayList<>();
    try (var workers = Executors.newFixedThreadPool(2)) {
      try {
        for (int index = 0; index < 2; index++) {
          futures.add(
              workers.submit(
                  () -> {
                    PetTable table = new PetTable();
                    OperationObservation observation =
                        java.util.Objects.requireNonNull(dispatcher.startOperation(
                            ExecutionOperationKind.FETCH_LIST, fixture.dialect().id()));
                    QueryCompilation<Long> compiled =
                        compileObservedSelection(
                            fixture, table, SelectedResult.requiredScalar(table.id), observation);
                    observation.completeSucceeded(ExecutionTermination.COMPLETED);
                    return compiled;
                  }));
        }
        assertTrue(fixture.dialect().entered.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fixture.stats().missCount() < 2 && System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
        assertEquals(2, fixture.stats().missCount());
        assertTrue(events.isEmpty());
      } finally {
        fixture.dialect().release.countDown();
      }
      assertSame(
          futures.getFirst().get(5, TimeUnit.SECONDS).plan(),
          futures.getLast().get(5, TimeUnit.SECONDS).plan());
    }
    assertEquals(1, events.size());
    assertEquals(ExecutionOutcome.SUCCEEDED, events.getFirst().outcome());
    assertEquals(1, fixture.dialect().renders.get());
  }

  @Test
  void assemblerSignsBuiltInResultAndParameterIdentitiesWithoutBypassingL1() throws Exception {
    PetTable table = new PetTable();
    NonNullSelectable<Long> literal =
        new NonNullExpressionSelectable<>(LiteralExpression.one(Long.class));
    var assembler =
        new QueryPlanKeyAssembler(
            REGISTRY,
            new QueryPlanKey.IdentityScope(REGISTRY),
            QueryPlanKey.DialectIdentity.from(new CountingDialect(true)),
            true);
    var variant = QueryPlanKey.PlanVariant.content(QueryPaginationShape.none());
    var resultAnalysis =
        ResolvedStatement.analyze(
            new SelectStatement(
                List.of(SelectableSupport.expression(literal)),
                QueryTableModel.of(table).tableExpression()));
    assertTrue(assembler.assemble(
        resultAnalysis, SelectedResult.requiredScalar(literal), variant, List.of(), List.of())
        instanceof QueryPlanKeyAssembler.Cacheable);

    SelectedResult<Long> selected = SelectedResult.requiredScalar(table.id);
    CompiledQueryStructure structure =
        QueryTestSupport.compile(selected, table, List.of(), literal.eq(1L));
    var parameterAnalysis =
        ResolvedStatement.analyze(
            new SelectStatement(structure.selections(), structure.fromClause(), structure.where()));
    assertTrue(assembler.assemble(
            parameterAnalysis,
            selected,
            variant,
            List.of(
                QueryPlanCompiler.LogicalParameter.codec(
                    structure.parameterSlots().getFirst(), JdbcCodecs.LONG)),
            structure.parameterSources()) instanceof QueryPlanKeyAssembler.Cacheable);

    // Standard literals use the same built-in identity for results and comparison binders.
    Fixture fixture = fixture(16, true);
    QueryCompilation<Long> literalResult = compile(fixture.operations().select(literal).from(table));
    assertEquals(0, literalResult.plan().parameterCount());
    QueryCompilation<Long> literalParameter =
        compile(fixture.operations().select(table.id).from(table).where(literal.eq(1L)));
    assertEquals(List.of(1L), bindings(literalParameter));
    assertSame(literalResult.plan(), compile(fixture.operations().select(literal).from(table)).plan());
    assertSame(literalParameter.plan(),
        compile(fixture.operations().select(table.id).from(table).where(literal.eq(2L))).plan());
    assertEquals(2, fixture.dialect().renders.get());
    assertEquals(new QueryPlanCacheStatistics(2, 2, 0, 0, 2, 16), fixture.stats());
  }

  @Test
  void aggregateAndStandardResultFamiliesShareAcrossValuesAndFreshObjects() throws Exception {
    for (int shape = 0; shape < 15; shape++) {
      Fixture fixture = fixture(16, true, true);
      QueryCompilation<?> first = compile(aggregateQuery(fixture, shape, 11L));
      QueryCompilation<?> second = compile(aggregateQuery(fixture, shape, 22L));
      assertSame(first.plan(), second.plan(), "shape " + shape);
      assertNotSame(first.ast(), second.ast());
      assertTrue(bindings(first).contains(11L), "shape " + shape);
      assertTrue(bindings(second).contains(22L), "shape " + shape);
      assertFalse(bindings(second).contains(11L));
      assertEquals(new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 16), fixture.stats());
      assertEquals(1, fixture.dialect().renders.get());
      assertEquals(1, fixture.dialect().lowerings.get());
    }
  }

  @Test
  void aggregateKindsDistinctGroupingResultTypesAndHavingRemainSeparate() {
    Fixture fixture = fixture(32, true, true);
    PetTable table = new PetTable();
    List<Selectable<?>> expressions = List.of(Sql.countAll(), Sql.count(table.id),
        Sql.countDistinct(table.id), Sql.min(table.id), Sql.max(table.id),
        Sql.sumAsBigInteger(table.id), Sql.avgAsBigDecimal(table.id));
    List<Object> plans = new ArrayList<>();
    for (Selectable<?> expression : expressions) {
      Object plan = compile(fixture.operations().select(expression).from(table)).plan();
      for (Object previous : plans) assertNotSame(previous, plan);
      plans.add(plan);
    }
    Object grouped = compile(fixture.operations().select(Sql.countAll()).from(table)
        .groupBy(table.name)).plan();
    Object otherGrouping = compile(fixture.operations().select(Sql.countAll()).from(table)
        .groupBy(table.id)).plan();
    Object having = compile(fixture.operations().select(Sql.countAll()).from(table)
        .groupBy(table.name).having(Sql.countAll().gt(1L))).plan();
    assertNotSame(grouped, otherGrouping);
    assertNotSame(grouped, having);
    assertNotSame(plans.getFirst(), grouped);
    assertEquals(10, fixture.stats().size());
    assertEquals(10, fixture.stats().missCount());
  }

  @Test
  void groupedContentOffsetAndCountKeepIndependentLayoutsAndLocalReuse() throws Exception {
    Fixture fixture = fixture(16, true, true);
    DefaultSelectQuery<Long> first = grouped(fixture, 1L);
    DefaultSelectQuery<Long> second = grouped(fixture, 2L);
    QueryCompilation<Long> content = first.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> offset = first.compilation(new QueryPagination.Offset(3, 6));
    QueryCompilation<Long> count = first.countCompilation();
    assertNotSame(content.plan(), offset.plan());
    assertNotSame(content.plan(), count.plan());
    assertSame(content.plan(), second.compilation(QueryPagination.None.INSTANCE).plan());
    QueryCompilation<Long> later = second.compilation(new QueryPagination.Offset(9, 18));
    assertSame(offset.plan(), later.plan());
    assertEquals(List.of(2L, 12L, 9, 18L), bindings(later));
    QueryCompilation<Long> secondCount = second.countCompilation();
    assertSame(count.plan(), secondCount.plan());
    assertEquals(List.of(2L, 12L), bindings(secondCount));
    assertFalse(secondCount.plan().sql().contains("OFFSET"));
    assertSame(secondCount.plan(), second.countCompilation().plan());
    assertSame(later.plan(), second.compilation(new QueryPagination.Offset(1, 2)).plan());
    assertEquals(new QueryPlanCacheStatistics(3, 3, 0, 0, 3, 16), fixture.stats());
    assertEquals(3, fixture.dialect().renders.get());
  }

  @Test
  void parameterizedGroupingSharesTheLoweredRelationAndBindsOnlyCurrentValues() throws Exception {
    Fixture fixture = fixture(16, true, true);
    List<QueryCompilation<?>> compiled = new ArrayList<>();
    for (long value : List.of(11L, 22L)) {
      PetTable table = new PetTable();
      QueryParameter<Long> parameter = Sql.parameter(Long.class, "delta");
      var key = Sql.add(table.id, Sql.value(parameter));
      var query = fixture.operations().select(key).from(table).groupBy(key)
          .having(Sql.max(table.id).gt(parameter)).orderBy(key.asc()).bind(parameter, value);
      QueryCompilation<?> content = compile(query);
      assertTrue(content.plan().sql().contains("VALUES"));
      assertTrue(bindings(content).stream().allMatch(binding -> Long.valueOf(value).equals(binding)));
      assertEquals(List.of(0, 0), content.plan().renderedSql().parameters().stream()
          .map(slot -> slot.ordinal()).toList());
      compiled.add(content);
    }
    assertSame(compiled.getFirst().plan(), compiled.getLast().plan());
    assertEquals(new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 16), fixture.stats());
  }

  @Test
  void sameNamedIndependentParametersStayDistinctAfterGroupingAndCacheReuse() throws Exception {
    Fixture fixture = fixture(16, true, true);
    for (long value : List.of(1L, 2L)) {
      PetTable table = new PetTable();
      var first = Sql.parameter(Long.class, "same");
      var second = Sql.parameter(Long.class, "same");
      var query = fixture.operations().select(Sql.countAll()).from(table)
          .where(table.id.ge(first)).having(Sql.countAll().gt(second))
          .bind(first, value).bind(second, value + 10);
      assertEquals(List.of(value, value + 10), bindings(compile(query)));
      var illegalKey = Sql.add(table.id, Sql.value(first));
      var independentKey = Sql.add(table.id, Sql.value(second));
      assertEquals(SkisErrorCode.QUERY_GROUPING_CONTRACT,
          assertThrows(QueryValidationException.class, () -> compile(fixture.operations()
              .select(illegalKey).from(table).groupBy(independentKey)
              .bind(first, value).bind(second, value))).code());
    }
    assertEquals(new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 16), fixture.stats());
  }

  @Test
  void pruningRetainsAggregateSubqueryDependenciesForContentAndCountInvalidation() {
    Fixture fixture = fixture(16, true, true);
    PetTable table = new PetTable();
    OwnerTable owner = new OwnerTable().as("aggregate_owner");
    var aggregate = Sql.scalar(Sql.select(Sql.max(owner.id)).from(owner));
    var query = (DefaultSelectQuery<Long>) fixture.operations().select(Sql.countAll()).from(table)
        .having(aggregate.in(List.of()));
    QueryCompilation<Long> first = query.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> count = query.countCompilation();
    assertFalse(first.plan().sql().contains("aggregate_owner"));
    assertFalse(count.plan().sql().contains("aggregate_owner"));
    assertEquals(2, fixture.catalog().invalidateProjectionPlans(OWNER));
    assertEquals(0, fixture.stats().size());
    assertSame(first.plan(), query.compilation(QueryPagination.None.INSTANCE).plan());
    assertSame(count.plan(), query.countCompilation().plan());
    var fresh = (DefaultSelectQuery<Long>) fixture.operations().select(Sql.countAll()).from(table)
        .having(aggregate.in(List.of()));
    assertNotSame(first.plan(), fresh.compilation(QueryPagination.None.INSTANCE).plan());
    assertNotSame(count.plan(), fresh.countCompilation().plan());
  }

  @Test
  void aggregateFailureBypassAndEvictionPreserveL0AndAllowFreshCompilation() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      Fixture fixture = fixture(mode == 1 ? 0 : 1, mode != 2, true);
      var query = grouped(fixture, 1L);
      QueryCompilation<Long> first = query.compilation(QueryPagination.None.INSTANCE);
      assertSame(first.plan(), query.compilation(QueryPagination.None.INSTANCE).plan());
      QueryCompilation<Long> next = grouped(fixture, 2L).compilation(QueryPagination.None.INSTANCE);
      if (mode == 0) {
        assertSame(first.plan(), next.plan());
        query.countCompilation();
        assertEquals(1, fixture.stats().evictionCount());
      } else {
        assertNotSame(first.plan(), next.plan());
        assertEquals(0, fixture.stats().missCount());
        assertEquals(0, fixture.stats().hitCount());
      }
      fixture.catalog().clearProjectionPlans();
      assertSame(first.plan(), query.compilation(QueryPagination.None.INSTANCE).plan());
      fixture.dialect().failNext = true;
      assertThrows(QueryValidationException.class,
          () -> grouped(fixture, 3L).compilation(QueryPagination.None.INSTANCE));
      assertEquals(0, fixture.stats().size());
      assertEquals(List.of(4L, 14L),
          bindings(grouped(fixture, 4L).compilation(QueryPagination.None.INSTANCE)));
    }
  }

  @Test
  void clearingOrInvalidatingDuringAggregateCompilationPreventsStalePublication() throws Exception {
    for (boolean clear : List.of(false, true)) {
      Fixture fixture = fixture(2, true, true);
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      fixture.dialect().entered = entered;
      fixture.dialect().release = release;
      try (var workers = Executors.newSingleThreadExecutor()) {
        Future<QueryCompilation<Long>> old = workers.submit(
            () -> grouped(fixture, 1L).compilation(QueryPagination.None.INSTANCE));
        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          if (clear) fixture.catalog().clearProjectionPlans();
          else assertEquals(0, fixture.catalog().invalidateProjectionPlans(PET));
          fixture.dialect().entered = null;
          fixture.dialect().release = null;
          QueryCompilation<Long> fresh = grouped(fixture, 2L).compilation(QueryPagination.None.INSTANCE);
          release.countDown();
          assertNotSame(old.get(5, TimeUnit.SECONDS).plan(), fresh.plan());
          assertSame(fresh.plan(), grouped(fixture, 3L).compilation(QueryPagination.None.INSTANCE).plan());
          assertEquals(1, fixture.stats().size());
          assertEquals(List.of(2L, 12L), bindings(fresh));
        } finally {
          release.countDown();
        }
      }
    }
  }

  @Test
  void freshAggregateMissesSingleFlightAndBindIndependentCallerValues() throws Exception {
    Fixture fixture = fixture(16, true, true);
    int callers = 8;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    fixture.dialect().entered = entered;
    fixture.dialect().release = release;
    List<Future<QueryCompilation<Long>>> futures = new ArrayList<>();
    try (var workers = Executors.newFixedThreadPool(callers)) {
      try {
        for (long value = 0; value < callers; value++) {
          long ownValue = value;
          futures.add(workers.submit(() -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            var result = grouped(fixture, ownValue).compilation(QueryPagination.None.INSTANCE);
            assertEquals(List.of(ownValue, ownValue + 10), bindings(result));
            return result;
          }));
        }
        start.countDown();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fixture.stats().missCount() < callers && System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
        assertEquals(callers, fixture.stats().missCount());
      } finally {
        release.countDown();
      }
      var first = futures.getFirst().get(5, TimeUnit.SECONDS);
      for (var future : futures) assertSame(first.plan(), future.get(5, TimeUnit.SECONDS).plan());
    }
    assertEquals(new QueryPlanCacheStatistics(0, callers, 0, 0, 1, 16), fixture.stats());
    assertEquals(1, fixture.dialect().renders.get());
    assertEquals(1, fixture.dialect().lowerings.get());
  }

  @Test
  void lazyOriginalAnalysisFailureRemainsInsideCompilationObservation() {
    List<CompilationCompletedEvent> events = new ArrayList<>();
    CountingDialect dialect = new CountingDialect(true, true);
    QueryPlanCatalog catalog = new QueryPlanCatalog(
        REGISTRY, dialect, 16, Duration.ofMinutes(30), compilationListener(events));
    dialect.renders.set(0);
    events.clear();
    PetTable table = new PetTable();
    var query = QueryTestSupport.operations(catalog).select(table.id).from(table).groupBy(table.name);

    QueryValidationException failure = assertThrows(QueryValidationException.class, query::fetchList);
    assertEquals(SkisErrorCode.QUERY_GROUPING_CONTRACT, failure.code());

    assertEquals(1, events.size());
    CompilationCompletedEvent event = events.getFirst();
    assertEquals(StatementPurpose.PRIMARY, event.purpose());
    assertEquals(ExecutionOutcome.FAILED, event.outcome());
    assertEquals(failure.diagnostics().stage(), event.failureStage());
    assertNull(event.sqlTemplate());
    assertNull(event.sqlFingerprint());
    assertEquals(0, dialect.renders.get());
    assertEquals(0, catalog.projectionPlanCacheStatistics().size());
  }

  @Test
  void aggregateL0AndL1ReuseSkipLoweringButKeepOriginalValidationAndNewFinalStages() {
    Fixture fixture = fixture(16, true, true);
    var first = grouped(fixture, 1L).andHaving(Sql.countAll().in(List.of()));
    var content = first.compilation(QueryPagination.None.INSTANCE);
    int validations = fixture.dialect().validations.get();
    assertSame(content.plan(), first.compilation(QueryPagination.None.INSTANCE).plan());
    assertEquals(validations, fixture.dialect().validations.get());
    assertSame(content.plan(), grouped(fixture, 2L).andHaving(Sql.countAll().in(List.of()))
        .compilation(QueryPagination.None.INSTANCE).plan());
    assertTrue(fixture.dialect().validations.get() > validations,
        "a new query still validates its original mapping and dialect contract");
    assertEquals(1, fixture.dialect().lowerings.get());
    assertEquals(1, fixture.dialect().renders.get());
    var offset = first.compilation(new QueryPagination.Offset(2, 4));
    var count = first.countCompilation();
    assertNotSame(content.ast(), offset.ast());
    assertNotSame(offset.ast(), count.ast());
    assertTrue(count.ast() instanceof CountAst);
    assertEquals(3, fixture.dialect().lowerings.get());
    assertEquals(3, fixture.dialect().renders.get());
    assertEquals(new QueryPlanCacheStatistics(1, 3, 0, 0, 3, 16), fixture.stats());
  }

  @Test
  void capturedMutableValuesAndTypedNullsReuseOnlyValueFreePlans() throws Exception {
    Fixture fixture = fixture(16, true, true);
    PetTable table = new PetTable();
    byte[] first = {1, 2};
    byte[] second = {3, 4};
    var a = fixture.operations().select(Sql.value(first)).from(table);
    var b = fixture.operations().select(Sql.value(second)).from(table);
    first[0] = 99;
    second[0] = 88;
    var compiledA = compile(a);
    var compiledB = compile(b);
    assertSame(compiledA.plan(), compiledB.plan());
    assertArrayEquals(new byte[] {1, 2}, (byte[]) bindings(compiledA).getFirst());
    assertArrayEquals(new byte[] {3, 4}, (byte[]) bindings(compiledB).getFirst());
    var nullA = compile(fixture.operations().select(Sql.value(Long.class, null)).from(table));
    var nullB = compile(fixture.operations().select(Sql.value(Long.class, null)).from(table));
    assertSame(nullA.plan(), nullB.plan());
    assertEquals(java.util.Collections.singletonList(null), bindings(nullB));
    assertEquals(new QueryPlanCacheStatistics(2, 2, 0, 0, 2, 16), fixture.stats());
  }

  private static SelectQuery<?> aggregateQuery(Fixture fixture, int shape, long value) {
    PetTable table = new PetTable();
    Selectable<?> expression = switch (shape) {
      case 0 -> Sql.countAll();
      case 1 -> Sql.count(table.name);
      case 2 -> Sql.countDistinct(table.id);
      case 3 -> Sql.min(table.id);
      case 4 -> Sql.max(table.id);
      case 5 -> Sql.sumAsBigInteger(table.id);
      case 6 -> Sql.avgAsBigDecimal(table.id);
      case 7 -> Sql.add(table.id, Sql.value(value));
      case 8 -> Sql.coalesce(Sql.min(table.id), Sql.value(value));
      case 9 -> Sql.cast(table.id, java.math.BigDecimal.class, SqlType.DECIMAL);
      case 10 -> Sql.caseWhen(table.id.gt(value), table.id, Sql.value(value));
      case 11 -> Sql.concat(table.name, Sql.value("suffix"));
      case 12 -> {
        OwnerTable owner = new OwnerTable().as("scalar_owner");
        yield Sql.scalar(Sql.select(Sql.max(owner.id)).from(owner).where(owner.id.ge(value)));
      }
      case 13 -> {
        OwnerTable owner = new OwnerTable().as("derived_owner");
        var maximum = Sql.max(owner.id);
        var output = Sql.output(maximum, "maximum");
        var derived = Sql.derived(Sql.select(maximum).from(owner).where(owner.id.ge(value)), "totals", output);
        yield derived.column(output);
      }
      case 14 -> Sql.value(value);
      default -> throw new AssertionError(shape);
    };
    if (shape == 13) {
      var derived = ((DerivedColumnSelectable<?>) expression).relation();
      return fixture.operations().select(expression).from(derived);
    }
    return fixture.operations().select(expression).from(table).where(table.id.ge(value));
  }

  private static DefaultSelectQuery<Long> grouped(Fixture fixture, long value) {
    PetTable table = new PetTable();
    return (DefaultSelectQuery<Long>) fixture.operations().select(Sql.countAll()).from(table)
        .where(table.id.ge(value)).groupBy(table.name).having(Sql.countAll().gt(value + 10))
        .orderBy(table.name.asc());
  }

  @Test
  void assemblerReturnsExplicitDisabledUnstableAndForeignMetadataReasons() {
    PetTable table = new PetTable();
    var analysis =
        ResolvedStatement.analyze(
            new SelectStatement(
                QueryTableModel.of(table).selections(),
                QueryTableModel.of(table).tableExpression()));
    var identities = new QueryPlanKey.IdentityScope(REGISTRY);
    var dialect = QueryPlanKey.DialectIdentity.from(new CountingDialect(true));
    var variant = QueryPlanKey.PlanVariant.content(QueryPaginationShape.none());
    assertEquals(
        QueryPlanKeyAssembler.Bypass.CACHE_DISABLED,
        new QueryPlanKeyAssembler(REGISTRY, identities, dialect, false)
            .assemble(analysis, SelectedResult.entity(table), variant, List.of(), List.of()));
    assertEquals(
        QueryPlanKeyAssembler.Bypass.UNSTABLE_DIALECT,
        new QueryPlanKeyAssembler(REGISTRY, identities, java.util.Optional.empty(), true)
            .assemble(analysis, SelectedResult.entity(table), variant, List.of(), List.of()));
    assertEquals(
        QueryPlanKeyAssembler.Bypass.UNREGISTERED_DEPENDENCY,
        new QueryPlanKeyAssembler(EntityRuntimeRegistry.empty(), identities, dialect, true)
            .assemble(analysis, SelectedResult.entity(table), variant, List.of(), List.of()));
  }

  private static ExecutionListenerDispatcher compilationListener(
      List<CompilationCompletedEvent> events) {
    return ExecutionListenerDispatcher.of(
        List.of(
            new ExecutionListener() {
              @Override
              public void compilationCompleted(CompilationCompletedEvent event) {
                events.add(event);
              }
            }));
  }

  private static <R> QueryCompilation<R> compileObservedSelection(
      Fixture fixture,
      PetTable table,
      SelectedResult<R> selected,
      OperationObservation observation) {
    CompiledQueryStructure structure = QueryTestSupport.compile(selected, table, List.of(), null);
    return fixture
        .catalog()
        .compiler()
        .compileSelection(
            selected,
            structure,
            List.of(),
            false,
            QueryPagination.None.INSTANCE,
            List.of(),
            QueryTestSupport.arguments(structure),
            observation,
            StatementPurpose.PRIMARY);
  }

  private static DefaultSelectQuery<Long> nested(Fixture fixture, int shape, long value) {
    PetTable outer = new PetTable().as("p");
    OwnerTable inner = new OwnerTable().as("q");
    QueryParameter<Long> parameter = Sql.parameter(Long.class);
    QueryParameters parameters = QueryParameters.of(parameter, value);
    var lookup = Sql.select(inner.id).from(inner).where(inner.id.ge(parameter));
    return switch (shape) {
      case 0 ->
          (DefaultSelectQuery<Long>)
              fixture
                  .operations()
                  .select(outer.id)
                  .from(outer)
                  .join(inner)
                  .on(outer.id.eq(inner.id).and(inner.id.ge(value)));
      case 1 ->
          (DefaultSelectQuery<Long>)
              fixture
                  .operations()
                  .query(
                      Sql.select(outer.id)
                          .from(outer)
                          .where(Sql.exists(lookup.and(inner.id.eq(outer.id)))))
                  .bind(parameter, value);
      case 2 ->
          (DefaultSelectQuery<Long>)
              fixture
                  .operations()
                  .query(Sql.select(outer.id).from(outer).where(outer.id.in(lookup)))
                  .bind(parameter, value);
      case 3 ->
          query(
              fixture,
              SelectQueryState.create(SelectedResult.nullableScalar(Sql.scalar(lookup)), outer),
              parameters);
      case 4, 5 -> {
        var output = Sql.output(inner.id, "id");
        DerivedRelation derived = Sql.derived(lookup, "d", output);
        yield shape == 4
            ? (DefaultSelectQuery<Long>)
                fixture
                    .operations()
                    .query(
                        Sql.select(outer.id)
                            .from(outer)
                            .join(derived)
                            .on(outer.id.eq(derived.column(output))))
                    .bind(parameter, value)
            : (DefaultSelectQuery<Long>)
                fixture
                    .operations()
                    .query(Sql.select(derived.column(output)).from(derived))
                    .bind(parameter, value);
      }
      default -> throw new AssertionError(shape);
    };
  }

  private static DefaultSelectQuery<Long> scalar(Fixture fixture, long value) {
    return scalar(fixture, new PetTable(), value);
  }

  private static DefaultSelectQuery<Long> scalar(Fixture fixture, PetTable table, long value) {
    return (DefaultSelectQuery<Long>)
        fixture.operations().select(table.id).from(table).where(table.id.ge(value));
  }

  private static DefaultSelectQuery<Long> orderedScalar(
      Fixture fixture, PetTable table, long value) {
    return (DefaultSelectQuery<Long>)
        fixture
            .operations()
            .select(table.id)
            .from(table)
            .where(table.id.ge(value))
            .orderBy(table.name.asc().nullsFirst(), table.id.asc());
  }

  private static <R extends @Nullable Object> QueryCompilation<R> compile(SelectQuery<R> query) {
    return ((DefaultSelectQuery<R>) query).compilation(QueryPagination.None.INSTANCE);
  }

  private static <R> QueryCompilation<R> compileSelected(
      Fixture fixture, PetTable table, SelectedResult<R> selected) {
    return query(fixture, SelectQueryState.create(selected, table))
        .compilation(QueryPagination.None.INSTANCE);
  }

  private static <R> DefaultSelectQuery<R> query(Fixture fixture, SelectQueryState<R> state) {
    return query(fixture, state, state.structure().validationStructure().parameters());
  }

  private static <R> DefaultSelectQuery<R> query(
      Fixture fixture, SelectQueryState<R> state, QueryParameters parameters) {
    return DefaultSelectQuery.create(
        (DefaultQueryOperations) fixture.operations(),
        fixture.catalog().compiler(),
        state,
        parameters);
  }

  private static ProjectionSelection<IdView> projection(PetTable table) {
    return ProjectionMapping.generated(
            GeneratedModelAbi.CURRENT,
            IdView.class,
            "id-view",
            List.of(new ProjectionMapping.Parameter(0, "id", Long.class, Nullability.NON_NULL, 0)),
            readers -> {
              ProjectionMapping.ValueReader<Long> id = readers.reader(0, Long.class);
              return (rs, context) -> new IdView(id.read(rs, context));
            })
        .bind(table.id);
  }

  private static List<@Nullable Object> bindings(QueryCompilation<?> compiled) throws Exception {
    List<@Nullable Object> values = new ArrayList<>();
    PreparedStatement statement =
        (PreparedStatement)
            Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                  if (method.getName().startsWith("set")) {
                    int index = (Integer) args[0];
                    while (values.size() < index) {
                      values.add(null);
                    }
                    values.set(index - 1, method.getName().equals("setNull") ? null : args[1]);
                  }
                  return null;
                });
    assertEquals(
        compiled.plan().parameterCount() + 1,
        compiled
            .plan()
            .parameterBinder()
            .bind(statement, 1, compiled.argument(), JdbcWriteContext.EMPTY));
    return values;
  }

  private static Fixture fixture(int capacity, boolean stable) {
    return fixture(capacity, stable, false);
  }

  private static Fixture fixture(int capacity, boolean stable, boolean aggregates) {
    CountingDialect dialect = new CountingDialect(stable, aggregates);
    QueryPlanCatalog catalog =
        new QueryPlanCatalog(REGISTRY, dialect, capacity, Duration.ofMinutes(30));
    QueryOperations operations = QueryTestSupport.operations(catalog);
    dialect.renders.set(0);
    dialect.validations.set(0);
    dialect.legacyRenders.set(0);
    dialect.legacyValidations.set(0);
    dialect.lowerings.set(0);
    return new Fixture(catalog, operations, dialect);
  }

  private record Fixture(
      QueryPlanCatalog catalog, QueryOperations operations, CountingDialect dialect) {
    QueryPlanCacheStatistics stats() {
      return catalog.projectionPlanCacheStatistics();
    }
  }

  private record Pet(Long id, @Nullable String name) {}

  private record Owner(Long id) {}

  private record ExternalRow(Long id) {}

  private record IdView(Long id) {}

  private static final class PetTable extends QueryTable<Pet> {
    private final NonNullSelectable<Long> id = nonNullQueryColumn(ID);
    private final Selectable<String> name = queryColumn(NAME);

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

  private static final class OwnerTable extends QueryTable<Owner> {
    private final NonNullSelectable<Long> id = nonNullQueryColumn(OWNER_ID);

    private OwnerTable() {
      super(OWNER);
    }

    private OwnerTable(String alias) {
      super(OWNER, alias);
    }

    @Override
    public OwnerTable as(String alias) {
      return new OwnerTable(alias);
    }
  }

  private static final class RenderSequence {
    private final AtomicInteger next = new AtomicInteger();
    private final List<CountDownLatch> entered =
        List.of(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
    private final List<CountDownLatch> release =
        List.of(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));

    private void awaitTurn() throws InterruptedException {
      int index = next.getAndIncrement();
      entered.get(index).countDown();
      assertTrue(release.get(index).await(15, TimeUnit.SECONDS));
    }
  }

  private static final class CountingDialect implements Dialect {
    private final AtomicInteger renders = new AtomicInteger();
    private final AtomicInteger validations = new AtomicInteger();
    private final AtomicInteger legacyRenders = new AtomicInteger();
    private final AtomicInteger legacyValidations = new AtomicInteger();
    private final AtomicInteger lowerings = new AtomicInteger();
    private final boolean stable;
    private final boolean aggregates;
    private final DialectCapabilities capabilities =
        DialectCapabilities.of(DialectFeature.values());
    private final SqlRenderer delegate = new StandardSqlRenderer(this);
    private volatile boolean failNext;
    private volatile @Nullable CountDownLatch entered;
    private volatile @Nullable CountDownLatch release;
    private volatile @Nullable RenderSequence sequence;

    private CountingDialect(boolean stable) {
      this(stable, false);
    }

    private CountingDialect(boolean stable, boolean aggregates) {
      this.stable = stable;
      this.aggregates = aggregates;
    }

    @Override
    public String id() {
      return "routing-test";
    }

    @Override
    public boolean hasStablePlanCacheIdentity() {
      return stable;
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
    public DialectLowering lowering() {
      DialectLowering target = aggregates
          ? StandardSqlLowering.postgresql(capabilities) : Dialect.super.lowering();
      return resolved -> {
        lowerings.incrementAndGet();
        return target.lower(resolved);
      };
    }

    @Override
    public void validate(io.skis.dialect.spi.ResolvedStatement resolved) {
      validations.incrementAndGet();
      Dialect.super.validate(resolved);
    }

    @Override
    public SqlRenderer renderer() {
      return input -> {
        renders.incrementAndGet();
        beforeRender();
        return delegate.render(input);
      };
    }

    private void assertAnalysisHandoff(StatementAst statement) {
      assertTrue(renders.get() > 0);
      assertTrue(validations.get() > 0);
    }

    private void beforeRender() {
      RenderSequence currentSequence = sequence;
      if (currentSequence != null) {
        try {
          currentSequence.awaitTurn();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }
      if (failNext) {
        failNext = false;
        throw new IllegalArgumentException("scripted render failure");
      }
      CountDownLatch signal = entered;
      CountDownLatch gate = release;
      if (signal != null && gate != null) {
        signal.countDown();
        try {
          assertTrue(gate.await(15, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }
    }
  }
}
