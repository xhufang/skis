package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.SqlRenderer;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mapping.JdbcCodecs;
import io.skis.mapping.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.ResolvedStructureKey;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import io.skis.sql.ast.SqlType;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class QueryPlanKeyTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, Long> ALTERNATE_ID =
      new PropertyMeta<>(1, "alternateId", Long.class, ColumnMeta.of("alternate_id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(2, "name", String.class, ColumnMeta.of("pet_name", false));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "shelter", "pet"),
          List.of(ID, ALTERNATE_ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);
  private static final EntityRuntimeRegistry RUNTIME_REGISTRY =
      EntityRuntimeRegistry.of(List.of(petRuntimeModel()));
  private static final QueryPlanKey.IdentityScope IDENTITIES =
      new QueryPlanKey.IdentityScope(RUNTIME_REGISTRY);

  private static final ResolvedStructureKey BASE_STRUCTURE =
      new ResolvedStructureKey.Node(
          "SELECT_BLOCK",
          List.of("root", "false"),
          List.of(ResolvedStructureKey.atom("FROM"), ResolvedStructureKey.atom("SELECT")));
  private static final PetTable KEY_TABLE = new PetTable();
  private static final QueryPlanKey.ResultShape ENTITY_RESULT =
      result(SelectedResult.entity(KEY_TABLE));
  private static final QueryPlanKey.ParameterShape LONG_PARAMETER =
      parameter(0, KEY_TABLE.id(), Nullability.NON_NULL);
  private static final DialectCapabilities POSTGRES_CAPABILITIES =
      DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
  private static final QueryPlanKey.DialectIdentity POSTGRES =
      new QueryPlanKey.DialectIdentity("postgresql", POSTGRES_CAPABILITIES, 17);

  @Test
  void catalogSourcesTheCompleteStableDialectIdentity() {
    Dialect dialect = testDialect("postgresql", POSTGRES_CAPABILITIES, 17, true);
    QueryPlanCatalog catalog =
        new QueryPlanCatalog(
            EntityRuntimeRegistry.empty(), dialect, 0, Duration.ofSeconds(1));
    QueryPlanCatalog changedCapabilities =
        new QueryPlanCatalog(
            EntityRuntimeRegistry.empty(),
            testDialect(
                "postgresql",
                DialectCapabilities.of(DialectFeature.CATALOG_QUALIFIED_TABLES),
                17,
                true),
            0,
            Duration.ofSeconds(1));
    QueryPlanCatalog changedBehaviorVersion =
        new QueryPlanCatalog(
            EntityRuntimeRegistry.empty(),
            testDialect("postgresql", POSTGRES_CAPABILITIES, 18, true),
            0,
            Duration.ofSeconds(1));
    QueryPlanKey.DialectIdentity identity = catalog.dialectIdentity().orElseThrow();
    QueryPlanKey.DialectIdentity capabilityChangedIdentity =
        changedCapabilities.dialectIdentity().orElseThrow();
    QueryPlanKey.DialectIdentity versionChangedIdentity =
        changedBehaviorVersion.dialectIdentity().orElseThrow();

    assertEquals(POSTGRES, identity);
    assertEquals(identity.capabilityVersion(), capabilityChangedIdentity.capabilityVersion());
    assertNotEquals(identity.capabilities(), capabilityChangedIdentity.capabilities());
    assertNotEquals(identity, capabilityChangedIdentity);
    assertNotEquals(identity, versionChangedIdentity);
  }

  @Test
  void catalogBypassesDialectsWithoutAnExplicitlyStableIdentity() {
    QueryPlanCatalog catalog =
        new QueryPlanCatalog(
            EntityRuntimeRegistry.empty(),
            testDialect("custom", DialectCapabilities.none(), 1, false),
            0,
            Duration.ofSeconds(1));

    assertTrue(catalog.dialectIdentity().isEmpty());
  }

  @Test
  void equalQueriesAcrossValuesAndObjectInstancesHaveTheSameKey() {
    PetTable firstTable = new PetTable();
    PetTable secondTable = new PetTable();
    String firstValue = "private-first-name";
    String secondValue = "private-second-name";

    QueryPlanKey first = actualKey(firstTable, firstTable.name().eq(firstValue));
    QueryPlanKey second = actualKey(secondTable, secondTable.name().eq(secondValue));

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertFalse(first.toString().contains(firstValue));
    assertFalse(second.toString().contains(secondValue));
  }

  @Test
  void differentResolvedQueryStructuresDoNotShareAKey() {
    PetTable equalityTable = new PetTable();
    PetTable rangeTable = new PetTable();

    assertNotEquals(
        actualKey(equalityTable, equalityTable.id().eq(7L)),
        actualKey(rangeTable, rangeTable.id().ge(7L)));
  }

  @Test
  void selectedResultDerivesStableResultContracts() {
    PetTable rebuiltTable = new PetTable();
    ProjectionSelection<PetNameView> firstProjection =
        projection("pet-name-view-v1", KEY_TABLE.name());
    ProjectionSelection<PetNameView> rebuiltProjection =
        projection("pet-name-view-v1", rebuiltTable.name());

    assertEquals(
        result(SelectedResult.entity(KEY_TABLE)),
        result(SelectedResult.entity(rebuiltTable)));
    assertNotEquals(
        result(SelectedResult.entity(KEY_TABLE)),
        result(SelectedResult.nullableEntity(KEY_TABLE)));
    assertNotEquals(
        result(SelectedResult.requiredScalar(KEY_TABLE.id())),
        result(SelectedResult.nullableScalar(KEY_TABLE.id())));
    assertNotEquals(
        result(SelectedResult.requiredScalar(KEY_TABLE.id())),
        result(SelectedResult.requiredScalar(KEY_TABLE.name())));
    assertEquals(
        result(SelectedResult.projection(firstProjection)),
        result(SelectedResult.projection(rebuiltProjection)));
    assertNotEquals(
        result(SelectedResult.projection(firstProjection)),
        result(
            SelectedResult.projection(
                projection("pet-name-view-v2", KEY_TABLE.name()))));
  }

  @Test
  void rejectsMetadataOutsideTheOwningRuntimeRegistry() {
    PropertyMeta<Pet, Long> foreignId =
        new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
    PropertyMeta<Pet, Long> foreignAlternateId =
        new PropertyMeta<>(1, "alternateId", Long.class, ColumnMeta.of("alternate_id", false));
    PropertyMeta<Pet, String> foreignName =
        new PropertyMeta<>(2, "name", String.class, ColumnMeta.of("pet_name", false));
    EntityMeta<Pet> foreignPet =
        EntityMeta.simple(
            Pet.class,
            new TableMeta("", "shelter", "pet"),
            List.of(foreignId, foreignAlternateId, foreignName),
            new PrimaryKeyMeta<>(List.of(foreignId)),
            false);
    PetTable foreignTable =
        new PetTable(foreignPet, foreignId, foreignAlternateId, foreignName);

    assertTrue(SelectedResult.entity(foreignTable).planKeyResultShape(IDENTITIES).isEmpty());
    assertTrue(
        QueryPlanKey.ParameterShape.codec(
                IDENTITIES,
                new ParameterSlot<>(
                    0, Long.class, SqlType.BIGINT, Nullability.NON_NULL),
                foreignTable.id())
            .isEmpty());
  }

  @Test
  void distinguishesEqualBinaryNamesLoadedByDifferentClassLoaders() throws IOException {
    byte[] classBytes = classBytes(LoaderScopedResult.class);
    String binaryName = LoaderScopedResult.class.getName();
    Class<?> firstType = new ByteArrayClassLoader().define(binaryName, classBytes);
    Class<?> secondType = new ByteArrayClassLoader().define(binaryName, classBytes);
    LoaderEntityFixture firstEntity = loaderEntity(firstType);
    LoaderEntityFixture secondEntity = loaderEntity(secondType);
    QueryPlanKey.IdentityScope loaderIdentities =
        new QueryPlanKey.IdentityScope(
            EntityRuntimeRegistry.of(
                List.of(firstEntity.runtimeModel(), secondEntity.runtimeModel())));
    LoaderEntityTable firstTable = new LoaderEntityTable(firstEntity);
    LoaderEntityTable secondTable = new LoaderEntityTable(secondEntity);
    ProjectionSelection<?> firstProjection =
        loaderScopedProjection(firstType, "loader-scoped-v1", KEY_TABLE.name());
    ProjectionSelection<?> secondProjection =
        loaderScopedProjection(secondType, "loader-scoped-v1", KEY_TABLE.name());

    assertEquals(firstType.getName(), secondType.getName());
    assertNotEquals(
        result(loaderIdentities, SelectedResult.entity(firstTable)),
        result(loaderIdentities, SelectedResult.entity(secondTable)));
    assertNotEquals(
        parameter(loaderIdentities, 0, firstTable.id(), Nullability.NON_NULL),
        parameter(loaderIdentities, 0, secondTable.id(), Nullability.NON_NULL));
    assertNotEquals(
        result(SelectedResult.projection(firstProjection)),
        result(SelectedResult.projection(secondProjection)));
  }

  @Test
  void isolatesEveryPlanAffectingIdentityDimension() {
    QueryPlanKey base =
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(LONG_PARAMETER),
            POSTGRES,
            Map.of());

    assertNotEquals(
        base,
        key(
            ResolvedStructureKey.node(
                "SELECT_BLOCK", List.of(ResolvedStructureKey.atom("DIFFERENT_WHERE"))),
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(LONG_PARAMETER),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            result(SelectedResult.nullableEntity(KEY_TABLE)),
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(LONG_PARAMETER),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.orderedContent(QueryPaginationShape.none()),
            List.of(LONG_PARAMETER),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.count(),
            List.of(LONG_PARAMETER),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(
                QueryPaginationShape.from(new QueryPagination.LimitOnly(1))),
            List.of(LONG_PARAMETER),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(
                QueryPaginationShape.from(new QueryPagination.Offset(1, 0))),
            List.of(LONG_PARAMETER),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.keyset(List.of(false))),
            List.of(LONG_PARAMETER),
            POSTGRES,
            Map.of()),
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.keyset(List.of(true))),
            List.of(LONG_PARAMETER),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(parameter(0, KEY_TABLE.name(), Nullability.NON_NULL)),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(parameter(0, KEY_TABLE.id(), Nullability.NULLABLE)),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(parameter(0, KEY_TABLE.alternateId(), Nullability.NON_NULL)),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(QueryPlanKey.ParameterShape.paginationLong(0)),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(
                parameter(0, KEY_TABLE.id(), Nullability.NON_NULL),
                parameter(1, KEY_TABLE.name(), Nullability.NON_NULL)),
            POSTGRES,
            Map.of()),
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(
                parameter(0, KEY_TABLE.name(), Nullability.NON_NULL),
                parameter(1, KEY_TABLE.id(), Nullability.NON_NULL)),
            POSTGRES,
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(LONG_PARAMETER),
            new QueryPlanKey.DialectIdentity("h2", POSTGRES_CAPABILITIES, 17),
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(LONG_PARAMETER),
            new QueryPlanKey.DialectIdentity("postgresql", POSTGRES_CAPABILITIES, 18),
            Map.of()));
    assertNotEquals(
        base,
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(LONG_PARAMETER),
            POSTGRES,
            Map.of("policy", "tenant-filter-v1")));
  }

  @Test
  void paginationShapeKeepsOnlyModeAndKeysetNullMarkers() {
    assertEquals(
        QueryPaginationShape.from(new QueryPagination.LimitOnly(10)),
        QueryPaginationShape.from(new QueryPagination.LimitOnly(99)));
    assertEquals(
        QueryPaginationShape.from(new QueryPagination.Offset(10, 7)),
        QueryPaginationShape.from(new QueryPagination.Offset(99, 999)));
    assertEquals(
        QueryPaginationShape.from(new QueryPagination.Keyset(10, List.of(7L, "a"))),
        QueryPaginationShape.from(new QueryPagination.Keyset(99, List.of(8L, "b"))));
    assertNotEquals(
        QueryPaginationShape.from(new QueryPagination.Keyset(10, listWithNull(7L, null))),
        QueryPaginationShape.from(new QueryPagination.Keyset(10, List.of(7L, "a"))));
  }

  @Test
  void normalizesStructuralContextOrder() {
    LinkedHashMap<String, String> first = new LinkedHashMap<>();
    first.put("policy", "tenant-filter-v1");
    first.put("schema", "public-v2");
    LinkedHashMap<String, String> second = new LinkedHashMap<>();
    second.put("schema", "public-v2");
    second.put("policy", "tenant-filter-v1");

    assertEquals(keyWithContext(first), keyWithContext(second));
    assertEquals(keyWithContext(first).hashCode(), keyWithContext(second).hashCode());
  }

  @Test
  void defensivelyCopiesEveryCallerOwnedCollection() {
    ArrayList<Boolean> nullMarkers = new ArrayList<>(List.of(false));
    ArrayList<QueryPlanKey.ParameterShape> parameters =
        new ArrayList<>(List.of(LONG_PARAMETER));
    HashMap<String, String> signatures = new HashMap<>(Map.of("policy", "none"));
    QueryPlanKey key =
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.keyset(nullMarkers)),
            parameters,
            POSTGRES,
            signatures);
    QueryPlanKey expected =
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.keyset(List.of(false))),
            List.of(LONG_PARAMETER),
            POSTGRES,
            Map.of("policy", "none"));
    int originalHash = key.hashCode();

    nullMarkers.set(0, true);
    parameters.clear();
    signatures.put("schema", "changed-after-construction");

    assertEquals(expected, key);
    assertEquals(originalHash, key.hashCode());
  }

  @Test
  void codecIdentityFollowsDerivedAndScalarOutputsButRejectsUnknownSources() {
    PetTable inner = new PetTable().as("inner_pet");
    NonNullDerivedOutput<Long> output = Sql.output(inner.id(), "pet_id");
    DerivedRelation derived =
        Sql.derived(Sql.select(inner.id()).from(inner), "published_pet", output);
    Selectable<Long> derivedColumn = derived.column(output);
    Selectable<Long> scalar = Sql.scalar(Sql.select(inner.id()).from(inner));
    Selectable<Long> literal =
        new NonNullExpressionSelectable<>(LiteralExpression.one(Long.class));

    assertEquals(
        parameter(0, inner.id(), Nullability.NON_NULL),
        parameter(0, derivedColumn, Nullability.NON_NULL));
    assertEquals(
        parameter(0, inner.id(), Nullability.NON_NULL),
        parameter(0, scalar, Nullability.NON_NULL));
    assertTrue(
        QueryPlanKey.ParameterShape.codec(
                IDENTITIES,
                new ParameterSlot<>(
                    0, Long.class, SqlType.BIGINT, Nullability.NON_NULL),
                literal)
            .isEmpty());
  }

  @Test
  void aHashCollisionStillRequiresFullStructuralEquality() {
    assertEquals("an".hashCode(), "c0".hashCode());
    QueryPlanKey first =
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(LONG_PARAMETER),
            new QueryPlanKey.DialectIdentity("an", DialectCapabilities.none(), 1),
            Map.of());
    QueryPlanKey second =
        key(
            BASE_STRUCTURE,
            ENTITY_RESULT,
            QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
            List.of(LONG_PARAMETER),
            new QueryPlanKey.DialectIdentity("c0", DialectCapabilities.none(), 1),
            Map.of());

    assertEquals(first.hashCode(), second.hashCode());
    assertNotEquals(first, second);
  }

  @Test
  void rejectsAmbiguousOrImpossibleKeyShapes() {
    QueryPlanKey.ParameterShape ordinalOne =
        parameter(1, KEY_TABLE.id(), Nullability.NON_NULL);
    ArrayList<Boolean> nullMarker = new ArrayList<>();
    nullMarker.add(null);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            key(
                BASE_STRUCTURE,
                ENTITY_RESULT,
                QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
                List.of(ordinalOne),
                POSTGRES,
                Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new QueryPaginationShape(QueryPaginationShape.Mode.OFFSET, List.of(false)));
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryPaginationShape.keyset(List.of()));
    assertThrows(
        NullPointerException.class,
        () -> QueryPaginationShape.keyset(nullMarker));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            QueryPlanKey.ParameterShape.codec(
                IDENTITIES,
                new ParameterSlot<>(
                    0, Long.class, SqlType.BIGINT, Nullability.NON_NULL),
                KEY_TABLE.name()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new QueryPlanKey.DialectIdentity(
                "PostgreSQL", DialectCapabilities.none(), 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            key(
                BASE_STRUCTURE,
                ENTITY_RESULT,
                QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
                List.of(LONG_PARAMETER),
                POSTGRES,
                Map.of("policy", " ")));
  }

  private static QueryPlanKey actualKey(PetTable table, QueryCondition condition) {
    CompiledQueryStructure compiled =
        QueryStructureCompiler.compile(table, List.of(), condition);
    SelectStatement statement =
        new SelectStatement(
            false,
            table.selections(),
            List.of(),
            compiled.fromClause(),
            compiled.where(),
            compiled.groupBy(),
            compiled.having(),
            compiled.orderBy(),
            null);
    ResolvedStructureKey structure =
        SemanticValidator.analyzeComplete(statement).structureKey();
    if (compiled.parameterSlots().size() != compiled.parameterSources().size()) {
      throw new AssertionError("compiled parameter descriptors and sources must stay aligned");
    }
    List<QueryPlanKey.ParameterShape> parameters = new ArrayList<>();
    for (int index = 0; index < compiled.parameterSlots().size(); index++) {
      parameters.add(
          QueryPlanKey.ParameterShape.codec(
                  IDENTITIES,
                  compiled.parameterSlots().get(index),
                  compiled.parameterSources().get(index))
              .orElseThrow());
    }
    return key(
        structure,
        result(SelectedResult.entity(table)),
        QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
        parameters,
        POSTGRES,
        Map.of());
  }

  private static QueryPlanKey keyWithContext(Map<String, String> contextSignatures) {
    return key(
        BASE_STRUCTURE,
        ENTITY_RESULT,
        QueryPlanKey.PlanVariant.content(QueryPaginationShape.none()),
        List.of(LONG_PARAMETER),
        POSTGRES,
        contextSignatures);
  }

  private static QueryPlanKey key(
      ResolvedStructureKey structure,
      QueryPlanKey.ResultShape resultShape,
      QueryPlanKey.PlanVariant variant,
      List<? extends QueryPlanKey.ParameterShape> parameters,
      QueryPlanKey.DialectIdentity dialect,
      Map<String, String> contextSignatures) {
    return new QueryPlanKey(
        structure, resultShape, variant, parameters, dialect, contextSignatures);
  }

  private static Dialect testDialect(
      String id,
      DialectCapabilities capabilities,
      int capabilityVersion,
      boolean stablePlanCacheIdentity) {
    return new Dialect() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public IdentifierRules identifierRules() {
        return identifier -> identifier;
      }

      @Override
      public DialectCapabilities capabilities() {
        return capabilities;
      }

      @Override
      public int capabilityVersion() {
        return capabilityVersion;
      }

      @Override
      public boolean hasStablePlanCacheIdentity() {
        return stablePlanCacheIdentity;
      }

      @Override
      public SqlRenderer renderer() {
        return statement -> {
          throw new AssertionError("empty catalog must not invoke the renderer");
        };
      }
    };
  }

  private static <V> QueryPlanKey.ParameterShape parameter(
      int ordinal, Selectable<V> source, Nullability nullability) {
    return parameter(IDENTITIES, ordinal, source, nullability);
  }

  private static <V> QueryPlanKey.ParameterShape parameter(
      QueryPlanKey.IdentityScope identities,
      int ordinal,
      Selectable<V> source,
      Nullability nullability) {
    return QueryPlanKey.ParameterShape.codec(
            identities,
            new ParameterSlot<>(
                ordinal, source.javaType(), source.sqlType(), nullability),
            source)
        .orElseThrow();
  }

  private static QueryPlanKey.ResultShape result(SelectedResult<?> selectedResult) {
    return result(IDENTITIES, selectedResult);
  }

  private static QueryPlanKey.ResultShape result(
      QueryPlanKey.IdentityScope identities, SelectedResult<?> selectedResult) {
    return selectedResult.planKeyResultShape(identities).orElseThrow();
  }

  private static EntityRuntimeModel<Pet> petRuntimeModel() {
    return new EntityRuntimeModel<>(
        PET,
        ignored -> (resultSet, context) -> new Pet(0L, 0L, ""),
        List.of(
            new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(ALTERNATE_ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(NAME, JdbcCodecs.STRING)));
  }

  private static ProjectionSelection<PetNameView> projection(
      String mappingId, NonNullSelectable<String> name) {
    return ProjectionMapping.generated(
            GeneratedModelAbi.CURRENT,
            PetNameView.class,
            mappingId,
            List.of(
                new ProjectionMapping.Parameter(
                    0, "name", String.class, Nullability.NON_NULL, 0)),
            readers -> {
              ProjectionMapping.ValueReader<String> reader =
                  readers.reader(0, String.class);
              return (resultSet, context) ->
                  new PetNameView(reader.read(resultSet, context));
            })
        .bind(name);
  }

  private static ProjectionSelection<?> loaderScopedProjection(
      Class<?> resultType, String mappingId, NonNullSelectable<String> name) {
    return ProjectionMapping.generated(
            GeneratedModelAbi.CURRENT,
            resultType,
            mappingId,
            List.of(
                new ProjectionMapping.Parameter(
                    0, "name", String.class, Nullability.NON_NULL, 0)),
            ignored -> (resultSet, context) -> null)
        .bind(name);
  }

  private static byte[] classBytes(Class<?> type) throws IOException {
    String resourceName = "/" + type.getName().replace('.', '/') + ".class";
    try (InputStream input =
        Objects.requireNonNull(type.getResourceAsStream(resourceName), "class resource")) {
      return input.readAllBytes();
    }
  }

  @SuppressWarnings("unchecked")
  private static LoaderEntityFixture loaderEntity(Class<?> javaType) {
    Class<Object> entityType = (Class<Object>) javaType;
    PropertyMeta<Object, Long> id =
        new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
    EntityMeta<Object> entity =
        EntityMeta.simple(
            entityType,
            new TableMeta("", "loader_scope", "entity"),
            List.of(id),
            new PrimaryKeyMeta<>(List.of(id)),
            false);
    EntityRuntimeModel<Object> runtimeModel =
        new EntityRuntimeModel<>(
            entity,
            ignored -> (resultSet, context) -> null,
            List.of(new PropertyRuntime<>(id, JdbcCodecs.LONG)));
    return new LoaderEntityFixture(entity, id, runtimeModel);
  }

  private static List<Object> listWithNull(Object first, Object second) {
    ArrayList<Object> values = new ArrayList<>();
    values.add(first);
    values.add(second);
    return values;
  }

  private record Pet(Long id, Long alternateId, String name) {}

  private record PetNameView(String name) {}

  private record LoaderEntityFixture(
      EntityMeta<Object> entity,
      PropertyMeta<Object, Long> id,
      EntityRuntimeModel<Object> runtimeModel) {}

  private static final class ByteArrayClassLoader extends ClassLoader {

    private ByteArrayClassLoader() {
      super(QueryPlanKeyTest.class.getClassLoader());
    }

    private Class<?> define(String name, byte[] classBytes) {
      return defineClass(name, classBytes, 0, classBytes.length);
    }
  }

  private static final class LoaderEntityTable extends QueryTable<Object> {

    private final LoaderEntityFixture fixture;
    private final NonNullQueryColumn<Object, Long> id;

    private LoaderEntityTable(LoaderEntityFixture fixture) {
      super(fixture.entity());
      this.fixture = fixture;
      this.id = nonNullQueryColumn(fixture.id());
    }

    private LoaderEntityTable(LoaderEntityFixture fixture, Identifier alias) {
      super(fixture.entity(), alias);
      this.fixture = fixture;
      this.id = nonNullQueryColumn(fixture.id());
    }

    private NonNullQueryColumn<Object, Long> id() {
      return id;
    }

    @Override
    public LoaderEntityTable as(String alias) {
      return new LoaderEntityTable(fixture, Identifier.of(alias));
    }

    @Override
    public LoaderEntityTable as(Identifier alias) {
      return new LoaderEntityTable(fixture, alias);
    }
  }

  private static final class PetTable extends QueryTable<Pet> {

    private final NonNullQueryColumn<Pet, Long> id;
    private final NonNullQueryColumn<Pet, Long> alternateId;
    private final NonNullQueryColumn<Pet, String> name;

    private PetTable() {
      this(PET, ID, ALTERNATE_ID, NAME);
    }

    private PetTable(Identifier alias) {
      this(PET, ID, ALTERNATE_ID, NAME, alias);
    }

    private PetTable(
        EntityMeta<Pet> entity,
        PropertyMeta<Pet, Long> id,
        PropertyMeta<Pet, Long> alternateId,
        PropertyMeta<Pet, String> name) {
      super(entity);
      this.id = nonNullQueryColumn(id);
      this.alternateId = nonNullQueryColumn(alternateId);
      this.name = nonNullQueryColumn(name);
    }

    private PetTable(
        EntityMeta<Pet> entity,
        PropertyMeta<Pet, Long> id,
        PropertyMeta<Pet, Long> alternateId,
        PropertyMeta<Pet, String> name,
        Identifier alias) {
      super(entity, alias);
      this.id = nonNullQueryColumn(id);
      this.alternateId = nonNullQueryColumn(alternateId);
      this.name = nonNullQueryColumn(name);
    }

    private NonNullQueryColumn<Pet, Long> id() {
      return id;
    }

    private NonNullQueryColumn<Pet, Long> alternateId() {
      return alternateId;
    }

    private NonNullQueryColumn<Pet, String> name() {
      return name;
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
}
