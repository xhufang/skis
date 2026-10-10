package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.skis.mapping.JdbcWriteContext;
import io.skis.mapping.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.QueryBlockAnalysis;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import io.skis.sql.ast.StatementAst;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SharedQueryPlanRoutingTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("name", true));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class, new TableMeta("", "", "pet"), List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)), false);
  private static final PropertyMeta<Owner, Long> OWNER_ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final EntityMeta<Owner> OWNER =
      EntityMeta.simple(
          Owner.class, new TableMeta("", "", "owner"), List.of(OWNER_ID),
          new PrimaryKeyMeta<>(List.of(OWNER_ID)), false);
  private static final EntityRuntimeRegistry REGISTRY =
      EntityRuntimeRegistry.of(List.of(new EntityRuntimeModel<>(
          PET,
          layout -> (rs, context) -> new Pet(rs.getLong(1), rs.getString(2)),
          List.of(new PropertyRuntime<>(ID, JdbcCodecs.LONG),
              new PropertyRuntime<>(NAME, JdbcCodecs.STRING))),
          new EntityRuntimeModel<>(OWNER,
              layout -> (rs, context) -> new Owner(rs.getLong(1)),
              List.of(new PropertyRuntime<>(OWNER_ID, JdbcCodecs.LONG)))));

  @Test
  void freshQueriesSharePlansButKeepTheirOwnAstAndArgumentsAndL0SkipsL1() throws Exception {
    Fixture fixture = fixture(16, true);
    DefaultSelectQuery<Pet, Long> first = scalar(fixture, 1L);
    QueryCompilation<Long> a = first.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> local = first.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> b = scalar(fixture, 2L).compilation(QueryPagination.None.INSTANCE);

    assertSame(a.plan(), local.plan());
    assertSame(a.ast(), local.ast());
    assertSame(a.plan(), b.plan());
    assertNotSame(a.ast(), b.ast());
    assertNotSame(((SelectStatement) a.ast()).fromClause(), ((SelectStatement) b.ast()).fromClause());
    assertEquals(List.of(1L), bindings(a));
    assertEquals(List.of(2L), bindings(b));
    assertEquals(new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 16), fixture.stats());
    assertEquals(1, fixture.dialect().renders.get());
    assertEquals(2, fixture.dialect().validations.get());
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
    DefaultSelectQuery<?, Long> query =
        (DefaultSelectQuery<?, Long>)
            fixture
                .operations()
                .select(pet.id)
                .from(pet)
                .leftJoin(owner)
                .on(pet.id.eq(owner.id))
                .orderBy(pet.id.asc());

    QueryCompilation<Long> content = query.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> offset = query.compilation(new QueryPagination.Offset(10, 20));
    QueryCompilation<Long> keyset =
        query.compilation(new QueryPagination.Keyset(10, List.of(7L)));

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
            pet, List.of(new QueryJoin(JoinType.INNER, owner, on)), null);
    CompiledQueryStructure leftStructure =
        QueryTestSupport.compile(
            pet, List.of(new QueryJoin(JoinType.LEFT, owner, on)), null);

    TableRuntimeScope innerScope =
        TableRuntimeScope.resolve(REGISTRY, innerStructure.fromClause());
    TableRuntimeScope leftScope = innerScope.forFromClause(leftStructure.fromClause());

    assertSame(innerScope.require(owner).model(), leftScope.require(owner).model());
    assertEquals(Nullability.NON_NULL, innerScope.effectiveNullability(owner.id));
    assertEquals(Nullability.NULLABLE, leftScope.effectiveNullability(owner.id));
  }

  @Test
  void runtimeScopeFallsBackForChangedEntityIdentityOrdinalAndSourceCount() {
    PetTable pet = new PetTable().as("p");
    OwnerTable owner = new OwnerTable().as("o");
    CompiledQueryStructure original =
        QueryTestSupport.compile(
            pet, List.of(new QueryJoin(JoinType.CROSS, owner, null)), null);
    TableRuntimeScope scope = TableRuntimeScope.resolve(REGISTRY, original.fromClause());

    PetTable replacementPet = new PetTable().as("p");
    CompiledQueryStructure changedIdentity =
        QueryTestSupport.compile(
            replacementPet, List.of(new QueryJoin(JoinType.CROSS, owner, null)), null);
    TableRuntimeScope identityScope = scope.forFromClause(changedIdentity.fromClause());
    assertEquals(0, identityScope.require(replacementPet).occurrenceOrdinal());
    assertThrows(QueryValidationException.class, () -> identityScope.require(pet));

    CompiledQueryStructure changedOrdinals =
        QueryTestSupport.compile(
            owner, List.of(new QueryJoin(JoinType.CROSS, pet, null)), null);
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

    TableRuntimeScope scope =
        TableRuntimeScope.resolve(REGISTRY, originalStructure.fromClause());
    TableRuntimeScope replacementScope =
        scope.forFromClause(replacementStructure.fromClause());

    assertEquals(0, replacementScope.require(replacement));
    assertThrows(QueryValidationException.class, () -> replacementScope.require(original));
  }

  @Test
  void rewrittenMembershipValidatesOriginalAndFinalStructuresWithoutLegacyReanalysis() {
    Fixture fixture = fixture(16, true);
    PetTable pet = new PetTable().as("p");
    DefaultSelectQuery<?, Long> query =
        (DefaultSelectQuery<?, Long>)
            fixture.operations().select(pet.id).from(pet).where(pet.id.in(List.of()));

    QueryCompilation<Long> compilation = query.compilation(QueryPagination.None.INSTANCE);

    assertEquals(
        "SELECT \"p\".\"id\" FROM \"pet\" AS \"p\" WHERE 1 = 0",
        compilation.plan().sql());
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
    DefaultSelectQuery<Pet, Long> a = orderedScalar(fixture, first, 1L);
    DefaultSelectQuery<Pet, Long> b = orderedScalar(fixture, second, 2L);
    List<QueryPagination> firstPages = List.of(
        QueryPagination.None.INSTANCE,
        new QueryPagination.LimitOnly(3),
        new QueryPagination.Offset(3, 6),
        new QueryPagination.Keyset(3, List.of("Ada", 7L)),
        new QueryPagination.Keyset(3, Arrays.asList(null, 7L)));
    List<QueryPagination> secondPages = List.of(
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
    List<QueryPagination> pages = List.of(
        new QueryPagination.Offset(3, 6),
        new QueryPagination.Keyset(4, List.of("Ada", 7L)),
        new QueryPagination.Keyset(5, Arrays.asList(null, 8L)));
    List<QueryPagination> changedValues = List.of(
        new QueryPagination.Offset(9, 18),
        new QueryPagination.Keyset(10, List.of("Bea", 17L)),
        new QueryPagination.Keyset(11, Arrays.asList(null, 18L)));
    List<List<Object>> expectedBindings = List.of(
        List.of(1L, 3, 6L), List.of(1L, "Ada", "Ada", 7L, 4), List.of(1L, 8L, 5));
    List<List<Object>> changedBindings = List.of(
        List.of(1L, 9, 18L), List.of(1L, "Bea", "Bea", 17L, 10), List.of(1L, 18L, 11));
    Fixture baseline = fixture(0, true);
    List<QueryCompilation<Long>> expected = pages.stream()
        .map(page -> orderedScalar(baseline, new PetTable(), 1L).compilation(page)).toList();

    // Stable L1, disabled L1 and an unstable dialect all use the same query-local publication path.
    for (int mode = 0; mode < 3; mode++) {
      Fixture fixture = fixture(mode == 1 ? 0 : 16, mode != 2);
      DefaultSelectQuery<Pet, Long> query = orderedScalar(fixture, new PetTable(), 1L);
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
            assertPaginationCompilation(expected.get(index), completed, expectedBindings.get(index));
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
      assertEquals(mode == 0
          ? new QueryPlanCacheStatistics(3, 3, 0, 0, 3, 16)
          : new QueryPlanCacheStatistics(0, 0, 0, 0, 0, mode == 1 ? 0 : 16), fixture.stats());
    }
  }

  private static void assertPaginationCompilation(
      QueryCompilation<Long> expected, QueryCompilation<Long> actual, List<Object> values)
      throws Exception {
    assertEquals(expected.plan().sql(), actual.plan().sql());
    assertEquals(expected.plan().renderedSql().parameters(), actual.plan().renderedSql().parameters());
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
                QueryTestSupport.arguments(structure).withPagination(firstPage));
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
                QueryTestSupport.arguments(structure).withPagination(firstPage));
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
                QueryTestSupport.arguments(structure).withPagination(laterPage));
    QueryCompilation<Long> count =
        fixture
            .catalog()
            .compiler()
            .compileCount(selected, structure, false, QueryTestSupport.arguments(structure));
    assertEquals(content.plan().sql(), ordered.plan().sql());
    assertNotSame(content.plan(), ordered.plan());
    assertSame(ordered.plan(), repeated.plan());
    assertNotSame(content.plan(), count.plan());
    assertEquals(new QueryPlanCacheStatistics(1, 3, 0, 0, 3, 16), fixture.stats());
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
            "SELECT COUNT(*) FROM \"pet\" AS \"p\" WHERE 1 = 0");
    List<List<?>> expectedBindings =
        List.of(List.of(7L), List.of(7L), List.of(), List.of());
    for (int shape = 0; shape < 4; shape++) {
      Fixture fixture = fixture(16, true);
      PetTable table = new PetTable().as("p");
      DefaultSelectQuery<?, Long> query =
          switch (shape) {
            case 0 -> scalar(fixture, table, 7L);
            case 1 -> scalar(fixture, table, 7L).distinct();
            case 2 -> nested(fixture, 3, 7L);
            case 3 ->
                (DefaultSelectQuery<?, Long>)
                    fixture
                        .operations()
                        .select(table.id)
                        .from(table)
                        .where(table.id.in(List.of()));
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
    QueryCompilation<Long> required = compileSelected(fixture, table, SelectedResult.requiredScalar(table.id));
    QueryCompilation<Long> nullable = compileSelected(fixture, table, SelectedResult.nullableScalar(table.id));
    QueryCompilation<IdView> projected = compileSelected(fixture, table, SelectedResult.projection(projection(table)));
    PetTable rebuilt = new PetTable();
    QueryCompilation<IdView> repeated = compileSelected(fixture, rebuilt, SelectedResult.projection(projection(rebuilt)));
    assertNotSame(required.plan(), nullable.plan());
    assertNotSame(required.plan(), projected.plan());
    assertSame(projected.plan(), repeated.plan());
    Fixture otherCatalog = fixture(16, true);
    assertNotSame(required.plan(), compileSelected(otherCatalog, table, SelectedResult.requiredScalar(table.id)).plan());
    assertEquals(new QueryPlanCacheStatistics(1, 3, 0, 0, 3, 16), fixture.stats());
  }

  @Test
  void nestedEntityInvalidationRemovesContentAndCountButPreservesRootOnlyPlans() throws Exception {
    for (int shape = 0; shape < 6; shape++) {
      Fixture fixture = fixture(32, true);
      QueryCompilation<Long> rootOnly = scalar(fixture, 1L).compilation(QueryPagination.None.INSTANCE);
      DefaultSelectQuery<?, Long> first = nested(fixture, shape, 11L);
      DefaultSelectQuery<?, Long> second = nested(fixture, shape, 22L);
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
      assertSame(rootOnly.plan(), scalar(fixture, 2L).compilation(QueryPagination.None.INSTANCE).plan());
      DefaultSelectQuery<?, Long> rebuilt = nested(fixture, shape, 33L);
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
      QueryCompilation<Long> compilation = compile(
          fixture.operations().select(table.id).from(table).where(repeated.and(repeated)));
      assertEquals(List.of(value, value), bindings(compilation));
      assertEquals(List.of(0, 0), compilation.plan().renderedSql().parameters().stream()
          .map(slot -> slot.ordinal()).toList());
    }
    assertEquals(new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 16), fixture.stats());
  }

  @Test
  void membershipLengthDistinctCountAndNullableBinderShapesAreIsolated() throws Exception {
    Fixture fixture = fixture(16, true);
    PetTable table = new PetTable();
    QueryCompilation<Long> two = compile(
        fixture.operations().select(table.id).from(table).where(table.id.in(List.of(1L, 2L))));
    QueryCompilation<Long> alsoTwo = compile(
        fixture.operations().select(table.id).from(table).where(table.id.in(List.of(3L, 4L))));
    QueryCompilation<Long> three = compile(
        fixture.operations().select(table.id).from(table).where(table.id.in(List.of(3L, 4L, 5L))));
    assertSame(two.plan(), alsoTwo.plan());
    assertNotSame(two.plan(), three.plan());
    assertEquals(List.of(3L, 4L), bindings(alsoTwo));
    assertEquals(List.of(3L, 4L, 5L), bindings(three));

    QueryParameter<Long> parameter = Sql.parameter(Long.class);
    var description = Sql.select(table.id).from(table).where(table.id.eq(parameter));
    QueryCompilation<Long> nullValue = compile(
        fixture.operations().query(description, QueryParameters.of(parameter, null)));
    QueryCompilation<Long> nonNullValue = compile(
        fixture.operations().query(description, QueryParameters.of(parameter, 6L)));
    QueryCompilation<Long> required = compile(
        fixture.operations().select(table.id).from(table).where(table.id.eq(6L)));
    assertSame(nullValue.plan(), nonNullValue.plan());
    assertNotSame(nonNullValue.plan(), required.plan());
    assertEquals(java.util.Collections.singletonList(null), bindings(nullValue));
    assertEquals(List.of(6L), bindings(nonNullValue));

    DefaultSelectQuery<Pet, Long> plain =
        (DefaultSelectQuery<Pet, Long>) fixture.operations().select(table.id).from(table);
    assertNotSame(plain.countCompilation().plan(), plain.distinct().countCompilation().plan());
  }

  @Test
  void disabledAndUnstableDialectBypassL1ButKeepL0AndWorkingL2() throws Exception {
    for (Fixture fixture : List.of(fixture(0, true), fixture(16, false))) {
      DefaultSelectQuery<Pet, Long> query = scalar(fixture, 1L);
      QueryCompilation<Long> first = query.compilation(QueryPagination.None.INSTANCE);
      assertSame(first.plan(), query.compilation(QueryPagination.None.INSTANCE).plan());
      QueryCompilation<Long> second = scalar(fixture, 2L).compilation(QueryPagination.None.INSTANCE);
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
      DefaultSelectQuery<Pet, Long> query =
          (DefaultSelectQuery<Pet, Long>) fixture.operations().select(table.id).from(table)
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
    DefaultSelectQuery<Pet, Long> original = scalar(fixture, 1L);
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
    assertThrows(QueryValidationException.class,
        () -> scalar(fixture, 1L).compilation(QueryPagination.None.INSTANCE));
    assertEquals(0, fixture.stats().size());
    scalar(fixture, 2L).compilation(QueryPagination.None.INSTANCE);
    PetTable root = new PetTable();
    PetTable foreignOccurrence = new PetTable();
    assertThrows(QueryValidationException.class,
        () -> compile(fixture.operations().select(root.id).from(root).where(foreignOccurrence.id.ge(3L))));
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
          futures.add(workers.submit(() -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            QueryCompilation<Long> compiled = scalar(fixture, ownValue).compilation(QueryPagination.None.INSTANCE);
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
  void assemblerReportsUnsafeIdentitiesWithoutClaimingUnmappedExpressionsCanExecute() {
    PetTable table = new PetTable();
    NonNullSelectable<Long> literal =
        new NonNullExpressionSelectable<>(LiteralExpression.one(Long.class));
    var assembler = new QueryPlanKeyAssembler(REGISTRY, new QueryPlanKey.IdentityScope(REGISTRY),
        QueryPlanKey.DialectIdentity.from(new CountingDialect(true)), true);
    var variant = QueryPlanKey.PlanVariant.content(QueryPaginationShape.none());
    var resultAnalysis = SemanticValidator.analyzeComplete(
        new SelectStatement(List.of(literal.expression()), table));
    assertEquals(QueryPlanKeyAssembler.Bypass.UNSAFE_RESULT_IDENTITY,
        assembler.assemble(resultAnalysis, SelectedResult.requiredScalar(literal), variant,
            List.of(), List.of()));

    SelectedResult<Long> selected = SelectedResult.requiredScalar(table.id);
    CompiledQueryStructure structure =
        QueryTestSupport.compile(selected, table, List.of(), literal.eq(1L));
    var parameterAnalysis = SemanticValidator.analyzeComplete(
        new SelectStatement(structure.selections(), structure.fromClause(), structure.where()));
    assertEquals(QueryPlanKeyAssembler.Bypass.UNSAFE_PARAMETER_IDENTITY,
        assembler.assemble(parameterAnalysis, selected, variant,
            List.of(QueryPlanCompiler.LogicalParameter.codec(
                structure.parameterSlots().getFirst(), JdbcCodecs.LONG)),
            structure.parameterSources()));

    // These are assembler defense cases. The current compiler rejects an unmapped literal earlier.
    Fixture fixture = fixture(16, true);
    assertThrows(QueryValidationException.class,
        () -> compile(fixture.operations().select(literal).from(table)));
    assertThrows(QueryValidationException.class,
        () -> compile(fixture.operations().select(table.id).from(table).where(literal.eq(1L))));
    assertEquals(new QueryPlanCacheStatistics(0, 0, 0, 0, 0, 16), fixture.stats());
  }

  @Test
  void assemblerReturnsExplicitDisabledUnstableAndForeignMetadataReasons() {
    PetTable table = new PetTable();
    var analysis = SemanticValidator.analyzeComplete(new SelectStatement(table.selections(), table));
    var identities = new QueryPlanKey.IdentityScope(REGISTRY);
    var dialect = QueryPlanKey.DialectIdentity.from(new CountingDialect(true));
    var variant = QueryPlanKey.PlanVariant.content(QueryPaginationShape.none());
    assertEquals(QueryPlanKeyAssembler.Bypass.CACHE_DISABLED,
        new QueryPlanKeyAssembler(REGISTRY, identities, dialect, false)
            .assemble(analysis, SelectedResult.entity(table), variant, List.of(), List.of()));
    assertEquals(QueryPlanKeyAssembler.Bypass.UNSTABLE_DIALECT,
        new QueryPlanKeyAssembler(REGISTRY, identities, java.util.Optional.empty(), true)
            .assemble(analysis, SelectedResult.entity(table), variant, List.of(), List.of()));
    assertEquals(QueryPlanKeyAssembler.Bypass.UNREGISTERED_DEPENDENCY,
        new QueryPlanKeyAssembler(EntityRuntimeRegistry.empty(), identities, dialect, true)
            .assemble(analysis, SelectedResult.entity(table), variant, List.of(), List.of()));
  }

  private static DefaultSelectQuery<?, Long> nested(Fixture fixture, int shape, long value) {
    PetTable outer = new PetTable().as("p");
    OwnerTable inner = new OwnerTable().as("q");
    QueryParameter<Long> parameter = Sql.parameter(Long.class);
    QueryParameters parameters = QueryParameters.of(parameter, value);
    var lookup = Sql.select(inner.id).from(inner).where(inner.id.ge(parameter));
    return switch (shape) {
      case 0 -> (DefaultSelectQuery<?, Long>) fixture.operations().select(outer.id).from(outer)
          .join(inner).on(outer.id.eq(inner.id).and(inner.id.ge(value)));
      case 1 -> (DefaultSelectQuery<?, Long>) fixture.operations().query(
          Sql.select(outer.id).from(outer).where(Sql.exists(lookup.and(inner.id.eq(outer.id)))),
          parameters);
      case 2 -> (DefaultSelectQuery<?, Long>) fixture.operations().query(
          Sql.select(outer.id).from(outer).where(outer.id.in(lookup)), parameters);
      case 3 -> query(fixture,
          SelectQueryState.create(SelectedResult.nullableScalar(Sql.scalar(lookup)), outer), parameters);
      case 4, 5 -> {
        var output = Sql.output(inner.id, "id");
        DerivedRelation derived = Sql.derived(lookup, "d", output);
        yield shape == 4
            ? (DefaultSelectQuery<?, Long>) fixture.operations().query(
                Sql.select(outer.id).from(outer).join(derived).on(outer.id.eq(derived.column(output))),
                parameters)
            : (DefaultSelectQuery<?, Long>) fixture.operations().query(
                Sql.select(derived.column(output)).from(derived), parameters);
      }
      default -> throw new AssertionError(shape);
    };
  }

  private static DefaultSelectQuery<Pet, Long> scalar(Fixture fixture, long value) {
    return scalar(fixture, new PetTable(), value);
  }

  private static DefaultSelectQuery<Pet, Long> scalar(
      Fixture fixture, PetTable table, long value) {
    return (DefaultSelectQuery<Pet, Long>)
        fixture.operations().select(table.id).from(table).where(table.id.ge(value));
  }

  private static DefaultSelectQuery<Pet, Long> orderedScalar(Fixture fixture, PetTable table, long value) {
    return (DefaultSelectQuery<Pet, Long>) fixture.operations().select(table.id).from(table)
        .where(table.id.ge(value)).orderBy(table.name.asc().nullsFirst(), table.id.asc());
  }

  private static <R> QueryCompilation<R> compile(SelectQuery<?, R> query) {
    return ((DefaultSelectQuery<?, R>) query).compilation(QueryPagination.None.INSTANCE);
  }

  private static <R> QueryCompilation<R> compileSelected(Fixture fixture, PetTable table, SelectedResult<R> selected) {
    return query(fixture, SelectQueryState.create(selected, table)).compilation(QueryPagination.None.INSTANCE);
  }

  private static <R> DefaultSelectQuery<?, R> query(Fixture fixture, SelectQueryState<R> state) {
    return query(fixture, state, state.structure().validationStructure().parameters());
  }

  private static <R> DefaultSelectQuery<?, R> query(
      Fixture fixture, SelectQueryState<R> state, QueryParameters parameters) {
    return DefaultSelectQuery.create(
        (DefaultQueryOperations) fixture.operations(), fixture.catalog().compiler(), state, parameters);
  }

  private static ProjectionSelection<IdView> projection(PetTable table) {
    return ProjectionMapping.generated(
        GeneratedModelAbi.CURRENT, IdView.class, "id-view",
        List.of(new ProjectionMapping.Parameter(0, "id", Long.class, Nullability.NON_NULL, 0)),
        readers -> {
          ProjectionMapping.ValueReader<Long> id = readers.reader(0, Long.class);
          return (rs, context) -> new IdView(id.read(rs, context));
        }).bind(table.id);
  }

  private static List<@Nullable Object> bindings(QueryCompilation<?> compiled) throws Exception {
    List<@Nullable Object> values = new ArrayList<>();
    PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
        PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
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
    assertEquals(compiled.plan().parameterCount() + 1,
        compiled.plan().parameterBinder().bind(statement, 1, compiled.argument(), JdbcWriteContext.EMPTY));
    return values;
  }

  private static Fixture fixture(int capacity, boolean stable) {
    CountingDialect dialect = new CountingDialect(stable);
    QueryPlanCatalog catalog = new QueryPlanCatalog(REGISTRY, dialect, capacity, Duration.ofMinutes(30));
    QueryOperations operations = QueryTestSupport.operations(catalog);
    dialect.renders.set(0);
    dialect.validations.set(0);
    dialect.legacyRenders.set(0);
    dialect.legacyValidations.set(0);
    dialect.validatedAnalyses.clear();
    dialect.renderedAnalyses.clear();
    return new Fixture(catalog, operations, dialect);
  }

  private record Fixture(QueryPlanCatalog catalog, QueryOperations operations, CountingDialect dialect) {
    QueryPlanCacheStatistics stats() {
      return catalog.projectionPlanCacheStatistics();
    }
  }

  private record Pet(Long id, @Nullable String name) {}
  private record Owner(Long id) {}
  private record ExternalRow(Long id) {}
  private record IdView(Long id) {}

  private static final class PetTable extends QueryTable<Pet> {
    private final NonNullQueryColumn<Pet, Long> id = nonNullQueryColumn(ID);
    private final QueryColumn<Pet, String> name = queryColumn(NAME);

    private PetTable() {
      super(PET);
    }

    private PetTable(Identifier alias) {
      super(PET, alias);
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

  private static final class OwnerTable extends QueryTable<Owner> {
    private final NonNullQueryColumn<Owner, Long> id = nonNullQueryColumn(OWNER_ID);

    private OwnerTable() {
      super(OWNER);
    }

    private OwnerTable(Identifier alias) {
      super(OWNER, alias);
    }

    @Override
    public OwnerTable as(String alias) {
      return new OwnerTable(Identifier.of(alias));
    }

    @Override
    public OwnerTable as(Identifier alias) {
      return new OwnerTable(alias);
    }
  }

  private static final class RenderSequence {
    private final AtomicInteger next = new AtomicInteger();
    private final List<CountDownLatch> entered = List.of(
        new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
    private final List<CountDownLatch> release = List.of(
        new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));

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
    private final Map<StatementAst, QueryBlockAnalysis> validatedAnalyses =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<StatementAst, QueryBlockAnalysis> renderedAnalyses =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final boolean stable;
    private final DialectCapabilities capabilities = DialectCapabilities.of(DialectFeature.values());
    private final SqlRenderer delegate = new StandardSqlRenderer(id(), identifierRules(), capabilities);
    private volatile boolean failNext;
    private volatile @Nullable CountDownLatch entered;
    private volatile @Nullable CountDownLatch release;
    private volatile @Nullable RenderSequence sequence;

    private CountingDialect(boolean stable) {
      this.stable = stable;
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
    public boolean supportsResolvedQueryValidation() {
      return true;
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
    public void validate(StatementAst statement) {
      legacyValidations.incrementAndGet();
      Dialect.super.validate(statement);
    }

    @Override
    public void validate(StatementAst statement, QueryBlockAnalysis analysis) {
      validations.incrementAndGet();
      QueryBlockAnalysis previous = validatedAnalyses.put(statement, analysis);
      assertTrue(previous == null, "the same statement was resolved more than once");
      Dialect.super.validate(statement, analysis);
    }

    @Override
    public SqlRenderer renderer() {
      return new SqlRenderer() {
        @Override
        public io.skis.dialect.RenderedSql render(StatementAst statement) {
          legacyRenders.incrementAndGet();
          beforeRender();
          return delegate.render(statement);
        }

        @Override
        public io.skis.dialect.RenderedSql renderValidated(
            StatementAst statement, QueryBlockAnalysis analysis) {
          renders.incrementAndGet();
          assertSame(validatedAnalyses.get(statement), analysis);
          QueryBlockAnalysis previous = renderedAnalyses.put(statement, analysis);
          assertTrue(previous == null, "the same statement was rendered more than once");
          beforeRender();
          return delegate.renderValidated(statement, analysis);
        }
      };
    }

    private void assertAnalysisHandoff(StatementAst statement) {
      assertTrue(validatedAnalyses.containsKey(statement));
      assertTrue(renderedAnalyses.containsKey(statement));
      assertSame(validatedAnalyses.get(statement), renderedAnalyses.get(statement));
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
