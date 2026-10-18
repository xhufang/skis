package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.QueryValidationException;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.mapping.spi.RowReadContext;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.spi.ProjectionMapping;
import io.skis.query.spi.ProjectionSource;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.Nullability;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ProjectionQueryTest {

  private static final PropertyMeta<Pet, Long> PET_ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, Long> PET_OWNER_ID =
      new PropertyMeta<>(1, "ownerId", Long.class, ColumnMeta.of("owner_id", false));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "shelter", "pet"),
          List.of(PET_ID, PET_OWNER_ID),
          new PrimaryKeyMeta<>(List.of(PET_ID)),
          false);
  private static final PropertyMeta<Owner, Long> OWNER_ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Owner, String> OWNER_NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("owner_name", false));
  private static final EntityMeta<Owner> OWNER =
      EntityMeta.simple(
          Owner.class,
          new TableMeta("", "shelter", "owner"),
          List.of(OWNER_ID, OWNER_NAME),
          new PrimaryKeyMeta<>(List.of(OWNER_ID)),
          false);
  private static final PetTable PET_TABLE = new PetTable();
  private static final OwnerTable OWNER_TABLE = new OwnerTable();

  @Test
  void resolvesPhysicalColumnsIntoOneCompleteQueryLocalValueMapping() {
    EntityRuntimeRegistry registry =
        EntityRuntimeRegistry.of(List.of(petModel(), ownerModel()));
    CompiledQueryStructure structure =
        QueryTestSupport.compile(
            PET_TABLE,
            List.of(
                new QueryJoin(
                    JoinType.LEFT,
                    QueryRelations.of(OWNER_TABLE),
                    PET_TABLE.ownerId().eq(OWNER_TABLE.id()))),
            null);
    TableRuntimeScope scope = TableRuntimeScope.resolve(registry, structure.fromClause());

    ResolvedValueMapping<?> mapping =
        ResolvedValueMapping.resolve(OWNER_TABLE.name(), scope);

    assertSame(OWNER_TABLE.name(), mapping.selectable());
    assertEquals(String.class, mapping.javaType());
    assertEquals(OWNER_TABLE.name().sqlType(), mapping.sqlType());
    assertEquals(Nullability.NULLABLE, mapping.effectiveNullability());
    assertSame(JdbcCodecs.STRING, mapping.codec());
    assertEquals(1, mapping.sourceOccurrenceOrdinal());
  }

  @Test
  void bindsDefensivelyAndKeepsTheMappingQueryIndependent() {
    ProjectionMapping<PetOwnerView> mapping = nullableOwnerMapping();
    Selectable<?>[] selections = {PET_TABLE.id(), OWNER_TABLE.name()};

    ProjectionSelection<PetOwnerView> first = mapping.bind(selections);
    selections[0] = OWNER_TABLE.id();
    ProjectionSelection<PetOwnerView> second =
        mapping.bind(PET_TABLE.id(), OWNER_TABLE.name());

    assertEquals(List.of(PET_TABLE.id(), OWNER_TABLE.name()), first.selections());
    assertEquals(mapping.mappingId(), first.mappingId());
    assertNotSame(first, second);
    assertThrows(QueryValidationException.class, () -> mapping.bind(PET_TABLE.id()));
  }

  @Test
  void compilesAndDecodesACrossTableProjectionByOneBasedIndexes() throws Exception {
    ProjectionSelection<PetOwnerView> selected =
        nullableOwnerMapping().bind(PET_TABLE.id(), OWNER_TABLE.name());
    QueryCompilation<PetOwnerView> compilation =
        compile(
            selected,
            List.of(
                new QueryJoin(
                    JoinType.INNER,
                    QueryRelations.of(OWNER_TABLE),
                    PET_TABLE.ownerId().eq(OWNER_TABLE.id()))));

    assertEquals(
        "SELECT \"pet\".\"id\", \"owner\".\"owner_name\" "
            + "FROM \"shelter\".\"pet\" INNER JOIN \"shelter\".\"owner\" "
            + "ON \"pet\".\"owner_id\" = \"owner\".\"id\"",
        compilation.plan().sql());
    assertEquals(
        new PetOwnerView(7L, "Ada"),
        compilation
            .plan()
            .rowDecoder()
            .decode(resultSet(Map.of(1, 7L, 2, "Ada")), RowReadContext.EMPTY));
  }

  @Test
  void distinguishesTwoAliasesOfTheSameEntity() {
    OwnerTable reviewer = OWNER_TABLE.as("reviewer");
    ProjectionSelection<OwnerPair> selected =
        ownerPairMapping().bind(OWNER_TABLE.name(), reviewer.name());
    QueryCompilation<OwnerPair> compilation =
        compile(
            selected,
            List.of(
                new QueryJoin(
                    JoinType.INNER,
                    QueryRelations.of(OWNER_TABLE),
                    PET_TABLE.ownerId().eq(OWNER_TABLE.id())),
                new QueryJoin(
                    JoinType.INNER,
                    QueryRelations.of(reviewer),
                    OWNER_TABLE.id().eq(reviewer.id()))));

    assertTrue(compilation.plan().sql().contains("\"owner\".\"owner_name\""));
    assertTrue(compilation.plan().sql().contains("\"reviewer\".\"owner_name\""));
  }

  @Test
  void rejectsANonNullParameterAfterLeftJoinNullExtension() {
    ProjectionSelection<StrictPetOwnerView> selected =
        strictOwnerMapping().bind(PET_TABLE.id(), OWNER_TABLE.name());

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () ->
                compile(
                    selected,
                    List.of(
                        new QueryJoin(
                            JoinType.LEFT,
                            QueryRelations.of(OWNER_TABLE),
                            PET_TABLE.ownerId().eq(OWNER_TABLE.id())))));

    assertTrue(failure.getMessage().contains("parameter #2 'ownerName'"));
    assertTrue(failure.getMessage().contains("table occurrence #1"));
    assertTrue(failure.getMessage().contains("effective NULLABLE"));
    assertTrue(failure.getMessage().contains("java.lang.String / SQL VARCHAR"));
  }

  @Test
  void acceptsANullableParameterAfterLeftJoinNullExtension() throws Exception {
    ProjectionSelection<PetOwnerView> selected =
        nullableOwnerMapping().bind(PET_TABLE.id(), OWNER_TABLE.name());
    QueryCompilation<PetOwnerView> compilation =
        compile(
            selected,
            List.of(
                new QueryJoin(
                    JoinType.LEFT,
                    QueryRelations.of(OWNER_TABLE),
                    PET_TABLE.ownerId().eq(OWNER_TABLE.id()))));

    assertEquals(
        new PetOwnerView(7L, null),
        compilation
            .plan()
            .rowDecoder()
            .decode(resultSet(java.util.Collections.singletonMap(1, 7L)), RowReadContext.EMPTY));
  }

  @Test
  void bindsSpecialProjectionSourcesFromTheCompleteQueryBlock() throws Exception {
    DeclaredProjection<PetOwnerView> projection =
        new DeclaredProjection<>(
            nullableOwnerMapping(),
            List.of(ProjectionSource.of(PET_ID), ProjectionSource.of(OWNER_NAME)));
    QueryCompilation<PetOwnerView> compilation =
        compileSpecial(
            projection,
            List.of(
                new QueryJoin(
                    JoinType.INNER,
                    QueryRelations.of(OWNER_TABLE),
                    PET_TABLE.ownerId().eq(OWNER_TABLE.id()))));

    assertEquals(
        "SELECT \"pet\".\"id\", \"owner\".\"owner_name\" "
            + "FROM \"shelter\".\"pet\" INNER JOIN \"shelter\".\"owner\" "
            + "ON \"pet\".\"owner_id\" = \"owner\".\"id\"",
        compilation.plan().sql());
    assertEquals(
        new PetOwnerView(7L, "Ada"),
        compilation
            .plan()
            .rowDecoder()
            .decode(resultSet(Map.of(1, 7L, 2, "Ada")), RowReadContext.EMPTY));
  }

  @Test
  void validatesSpecialProjectionAliasesBeforeQueryBinding() {
    assertEquals(
        "owner_2", ProjectionSource.of(OWNER_NAME, "owner_2").alias().orElseThrow());
    assertEquals("_owner", ProjectionSource.of(OWNER_NAME, "_owner").alias().orElseThrow());

    for (String alias : List.of("", "bad alias", "bad-alias", "2owner", "所有者")) {
      IllegalArgumentException failure =
          assertThrows(
              IllegalArgumentException.class,
              () -> ProjectionSource.of(OWNER_NAME, alias),
              alias);
      assertTrue(failure.getMessage().contains("[A-Za-z_][A-Za-z0-9_]*"));
    }
  }

  @Test
  void rejectsMissingAndAmbiguousSpecialProjectionSources() {
    DeclaredProjection<PetOwnerView> projection =
        new DeclaredProjection<>(
            nullableOwnerMapping(),
            List.of(ProjectionSource.of(PET_ID), ProjectionSource.of(OWNER_NAME)));
    QueryValidationException missing =
        assertThrows(QueryValidationException.class, () -> compileSpecial(projection, List.of()));
    assertTrue(missing.getMessage().contains("no matching entity table"));

    OwnerTable reviewer = OWNER_TABLE.as("reviewer");
    QueryValidationException ambiguous =
        assertThrows(
            QueryValidationException.class,
            () ->
                compileSpecial(
                    projection,
                    List.of(
                        new QueryJoin(
                            JoinType.INNER,
                            QueryRelations.of(OWNER_TABLE),
                            PET_TABLE.ownerId().eq(OWNER_TABLE.id())),
                        new QueryJoin(
                            JoinType.INNER,
                            QueryRelations.of(reviewer),
                            OWNER_TABLE.id().eq(reviewer.id())))));
    assertTrue(ambiguous.getMessage().contains("ambiguous across 2 occurrences"));
  }

  @Test
  void bindsSpecialProjectionSelfJoinsByExactAlias() {
    OwnerTable owner = OWNER_TABLE.as("owner");
    OwnerTable reviewer = OWNER_TABLE.as("reviewer");
    DeclaredProjection<OwnerPair> projection =
        new DeclaredProjection<>(
            ownerPairMapping(),
            List.of(
                ProjectionSource.of(OWNER_NAME, "owner"),
                ProjectionSource.of(OWNER_NAME, "reviewer")));
    QueryCompilation<OwnerPair> compilation =
        compileSpecial(
            projection,
            List.of(
                new QueryJoin(
                    JoinType.INNER,
                    QueryRelations.of(owner),
                    PET_TABLE.ownerId().eq(owner.id())),
                new QueryJoin(
                    JoinType.INNER,
                    QueryRelations.of(reviewer),
                    owner.id().eq(reviewer.id()))));

    assertTrue(compilation.plan().sql().contains("\"owner\".\"owner_name\""));
    assertTrue(compilation.plan().sql().contains("\"reviewer\".\"owner_name\""));
  }

  @Test
  void rejectsASpecialProjectionAliasBoundToTheWrongEntity() {
    OwnerTable owner = OWNER_TABLE.as("owner");
    DeclaredProjection<PetOwnerView> projection =
        new DeclaredProjection<>(
            nullableOwnerMapping(),
            List.of(
                ProjectionSource.of(PET_ID, "owner"),
                ProjectionSource.of(OWNER_NAME, "owner")));

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () ->
                compileSpecial(
                    projection,
                    List.of(
                        new QueryJoin(
                            JoinType.INNER,
                            QueryRelations.of(owner),
                            PET_TABLE.ownerId().eq(owner.id())))));

    assertTrue(failure.getMessage().contains("uses alias 'owner' for entity"));
    assertTrue(failure.getMessage().contains("does not declare that property source"));
  }

  @Test
  void appliesFinalOuterJoinNullabilityToSpecialProjectionSources() {
    DeclaredProjection<StrictPetOwnerView> projection =
        new DeclaredProjection<>(
            strictOwnerMapping(),
            List.of(ProjectionSource.of(PET_ID), ProjectionSource.of(OWNER_NAME)));

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () ->
                compileSpecial(
                    projection,
                    List.of(
                        new QueryJoin(
                            JoinType.LEFT,
                            QueryRelations.of(OWNER_TABLE),
                            PET_TABLE.ownerId().eq(OWNER_TABLE.id())))));

    assertTrue(failure.getMessage().contains("parameter #2 'ownerName'"));
    assertTrue(failure.getMessage().contains("effective NULLABLE"));
  }

  @Test
  void propagatesScalarSubqueryCodecAndNullabilityIntoGeneratedProjections() throws Exception {
    OwnerTable innerOwner = OWNER_TABLE.as("scalar_owner");
    Selectable<String> ownerName =
        Sql.scalar(
            Sql.select(innerOwner.name())
                .from(innerOwner)
                .where(innerOwner.id().eq(PET_TABLE.ownerId())));
    ProjectionSelection<PetOwnerView> selected =
        nullableOwnerMapping().bind(PET_TABLE.id(), ownerName);

    QueryCompilation<PetOwnerView> compilation = compile(selected, List.of());

    assertEquals(
        "SELECT \"pet\".\"id\", (SELECT \"scalar_owner\".\"owner_name\" "
            + "FROM \"shelter\".\"owner\" AS \"scalar_owner\" WHERE "
            + "\"scalar_owner\".\"id\" = \"pet\".\"owner_id\") FROM \"shelter\".\"pet\"",
        compilation.plan().sql());
    assertEquals(
        new PetOwnerView(7L, "Ada"),
        compilation
            .plan()
            .rowDecoder()
            .decode(resultSet(Map.of(1, 7L, 2, "Ada")), RowReadContext.EMPTY));
    assertEquals(
        new PetOwnerView(7L, null),
        compilation
            .plan()
            .rowDecoder()
            .decode(
                resultSet(java.util.Collections.singletonMap(1, 7L)), RowReadContext.EMPTY));

    ProjectionSelection<StrictPetOwnerView> strict =
        strictOwnerMapping().bind(PET_TABLE.id(), ownerName);
    QueryValidationException failure =
        assertThrows(QueryValidationException.class, () -> compile(strict, List.of()));
    assertTrue(failure.getMessage().contains("effective NULLABLE"));
  }

  @Test
  void propagatesOuterJoinedDerivedColumnsIntoGeneratedNullableProjections() throws Exception {
    OwnerTable inner = OWNER_TABLE.as("projected_owner");
    NonNullDerivedOutput<Long> idOutput = Sql.output(inner.id(), "owner_id");
    NonNullDerivedOutput<String> nameOutput = Sql.output(inner.name(), "owner_name");
    DerivedRelation derived =
        Sql.derived(
            Sql.selectFrom(inner), "projected_owner_view", idOutput, nameOutput);
    NonNullSelectable<Long> derivedId = derived.column(idOutput);
    NonNullSelectable<String> derivedName = derived.column(nameOutput);
    QueryJoin leftJoin =
        new QueryJoin(
            JoinType.LEFT,
            QueryRelations.of(derived),
            PET_TABLE.ownerId().eq(derivedId));
    ProjectionSelection<PetOwnerView> selected =
        nullableOwnerMapping().bind(PET_TABLE.id(), derivedName);

    QueryCompilation<PetOwnerView> compilation = compile(selected, List.of(leftJoin));

    assertTrue(compilation.plan().sql().contains("LEFT JOIN (SELECT"));
    assertEquals(
        new PetOwnerView(7L, "Ada"),
        compilation
            .plan()
            .rowDecoder()
            .decode(resultSet(Map.of(1, 7L, 2, "Ada")), RowReadContext.EMPTY));
    assertEquals(
        new PetOwnerView(7L, null),
        compilation
            .plan()
            .rowDecoder()
            .decode(
                resultSet(java.util.Collections.singletonMap(1, 7L)), RowReadContext.EMPTY));

    ProjectionSelection<StrictPetOwnerView> strict =
        strictOwnerMapping().bind(PET_TABLE.id(), derivedName);
    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () -> compile(strict, List.of(leftJoin)));
    assertTrue(failure.getMessage().contains("effective NULLABLE"));
  }

  @Test
  void rejectsInvisibleSelectionsAndDefensiveJavaTypeMismatches() {
    ProjectionSelection<PetOwnerView> invisible =
        nullableOwnerMapping().bind(PET_TABLE.id(), OWNER_TABLE.name());
    assertThrows(QueryValidationException.class, () -> compile(invisible, List.of()));

    ProjectionMapping<WrongTypeView> mapping =
        ProjectionMapping.generated(
            GeneratedModelAbi.CURRENT,
            WrongTypeView.class,
            "wrong-type",
            List.of(
                new ProjectionMapping.Parameter(
                    0, "value", String.class, Nullability.NON_NULL, 0)),
            readers -> {
              ProjectionMapping.ValueReader<String> value = readers.reader(0, String.class);
              return (resultSet, context) -> new WrongTypeView(value.read(resultSet, context));
            });
    QueryValidationException typeFailure =
        assertThrows(
            QueryValidationException.class,
            () -> compile(mapping.bind(PET_TABLE.id()), List.of()));
    assertTrue(typeFailure.getMessage().contains("incompatible boxed Java type"));
  }

  @Test
  void failsFastForAnIncompatibleGeneratedAbi() {
    assertThrows(
        IncompatibleClassChangeError.class,
        () ->
            ProjectionMapping.generated(
                GeneratedModelAbi.CURRENT - 1,
                WrongTypeView.class,
                "old-abi",
                List.of(
                    new ProjectionMapping.Parameter(
                        0, "value", String.class, Nullability.NON_NULL, 0)),
                readers -> (resultSet, context) -> new WrongTypeView("unused")));
  }

  private static <R> QueryCompilation<R> compile(
      ProjectionSelection<R> selection, List<QueryJoin> joins) {
    QueryPlanCatalog catalog = catalog();
    CompiledQueryStructure structure =
        QueryTestSupport.compile(SelectedResult.projection(selection), PET_TABLE, joins, null);
    return catalog
        .compiler()
        .compileSelection(
            SelectedResult.projection(selection),
            structure,
            List.of(),
            false,
            QueryPagination.None.INSTANCE,
            List.of(),
            QueryTestSupport.arguments(structure));
  }

  private static <R> QueryCompilation<R> compileSpecial(
      ProjectionType<R> projection, List<QueryJoin> joins) {
    SelectQueryState<R> state =
        SelectQueryState.create(SelectedResult.specialProjection(projection), PET_TABLE);
    for (QueryJoin join : joins) {
      state = state.appendJoin(join.type(), join.right(), join.on());
    }
    CompiledQueryStructure structure = state.structure();
    return catalog()
        .compiler()
        .compileSelection(
            state.selected(),
            structure,
            List.of(),
            false,
            QueryPagination.None.INSTANCE,
            List.of(),
            QueryTestSupport.arguments(structure));
  }

  private static ProjectionMapping<PetOwnerView> nullableOwnerMapping() {
    return ProjectionMapping.generated(
        GeneratedModelAbi.CURRENT,
        PetOwnerView.class,
        "pet-owner-nullable",
        List.of(
            new ProjectionMapping.Parameter(0, "petId", Long.class, Nullability.NON_NULL, 0),
            new ProjectionMapping.Parameter(1, "ownerName", String.class, Nullability.NULLABLE, 1)),
        readers -> {
          ProjectionMapping.ValueReader<Long> petId = readers.reader(0, Long.class);
          ProjectionMapping.ValueReader<String> ownerName = readers.reader(1, String.class);
          return (resultSet, context) ->
              new PetOwnerView(
                  petId.read(resultSet, context), ownerName.read(resultSet, context));
        });
  }

  private static ProjectionMapping<StrictPetOwnerView> strictOwnerMapping() {
    return ProjectionMapping.generated(
        GeneratedModelAbi.CURRENT,
        StrictPetOwnerView.class,
        "pet-owner-strict",
        List.of(
            new ProjectionMapping.Parameter(0, "petId", Long.class, Nullability.NON_NULL, 0),
            new ProjectionMapping.Parameter(1, "ownerName", String.class, Nullability.NON_NULL, 1)),
        readers -> {
          ProjectionMapping.ValueReader<Long> petId = readers.reader(0, Long.class);
          ProjectionMapping.ValueReader<String> ownerName = readers.reader(1, String.class);
          return (resultSet, context) ->
              new StrictPetOwnerView(
                  petId.read(resultSet, context), ownerName.read(resultSet, context));
        });
  }

  private static ProjectionMapping<OwnerPair> ownerPairMapping() {
    return ProjectionMapping.generated(
        GeneratedModelAbi.CURRENT,
        OwnerPair.class,
        "owner-pair",
        List.of(
            new ProjectionMapping.Parameter(0, "owner", String.class, Nullability.NON_NULL, 0),
            new ProjectionMapping.Parameter(1, "reviewer", String.class, Nullability.NON_NULL, 1)),
        readers -> {
          ProjectionMapping.ValueReader<String> owner = readers.reader(0, String.class);
          ProjectionMapping.ValueReader<String> reviewer = readers.reader(1, String.class);
          return (resultSet, context) ->
              new OwnerPair(owner.read(resultSet, context), reviewer.read(resultSet, context));
        });
  }

  private static QueryPlanCatalog catalog() {
    return QueryRuntime.compile(
        EntityRuntimeRegistry.of(List.of(petModel(), ownerModel())), TestDialect.INSTANCE);
  }

  private static EntityRuntimeModel<Pet> petModel() {
    return new EntityRuntimeModel<>(
        PET,
        layout ->
            (resultSet, context) ->
                new Pet(
                    JdbcCodecs.LONG.read(resultSet, layout.requireIndex(0), context),
                    JdbcCodecs.LONG.read(resultSet, layout.requireIndex(1), context)),
        List.of(
            new PropertyRuntime<>(PET_ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(PET_OWNER_ID, JdbcCodecs.LONG)));
  }

  private static EntityRuntimeModel<Owner> ownerModel() {
    return new EntityRuntimeModel<>(
        OWNER,
        layout ->
            (resultSet, context) ->
                new Owner(
                    JdbcCodecs.LONG.read(resultSet, layout.requireIndex(0), context),
                    JdbcCodecs.STRING.read(resultSet, layout.requireIndex(1), context)),
        List.of(
            new PropertyRuntime<>(OWNER_ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(OWNER_NAME, JdbcCodecs.STRING)));
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
                  && arguments.length == 1
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

  private record Pet(Long id, Long ownerId) {}

  private record Owner(Long id, String name) {}

  private record PetOwnerView(Long petId, @Nullable String ownerName) {}

  private record StrictPetOwnerView(Long petId, String ownerName) {}

  private record OwnerPair(String owner, String reviewer) {}

  private record DeclaredProjection<R>(
      ProjectionMapping<R> mapping, List<ProjectionSource> sources)
      implements ProjectionType<R> {

    private DeclaredProjection {
      sources = List.copyOf(sources);
    }
  }

  private record WrongTypeView(String value) {}

  private static final class PetTable extends QueryTable<Pet> {

    private final NonNullSelectable<Long> id = nonNullQueryColumn(PET_ID);
    private final NonNullSelectable<Long> ownerId = nonNullQueryColumn(PET_OWNER_ID);

    private PetTable() {
      super(PET);
    }

    private PetTable(String alias) {
      super(PET, alias);
    }

    private NonNullSelectable<Long> id() {
      return id;
    }

    private NonNullSelectable<Long> ownerId() {
      return ownerId;
    }

    @Override
    public PetTable as(String alias) {
      return new PetTable(alias);
    }
  }

  private static final class OwnerTable extends QueryTable<Owner> {

    private final NonNullSelectable<Long> id = nonNullQueryColumn(OWNER_ID);
    private final NonNullSelectable<String> name = nonNullQueryColumn(OWNER_NAME);

    private OwnerTable() {
      super(OWNER);
    }

    private OwnerTable(String alias) {
      super(OWNER, alias);
    }

    private NonNullSelectable<Long> id() {
      return id;
    }

    private NonNullSelectable<String> name() {
      return name;
    }

    @Override
    public OwnerTable as(String alias) {
      return new OwnerTable(alias);
    }
  }

  private enum TestDialect implements Dialect {
    INSTANCE;

    private final DialectCapabilities capabilities =
        DialectCapabilities.of(
            DialectFeature.SCHEMA_QUALIFIED_TABLES,
            DialectFeature.INNER_JOIN,
            DialectFeature.LEFT_JOIN,
            DialectFeature.DERIVED_TABLE,
            DialectFeature.SCALAR_SUBQUERY,
            DialectFeature.CORRELATED_SUBQUERY);
    private final SqlRenderer renderer =
        new StandardSqlRenderer(id(), identifierRules(), capabilities);

    @Override
    public String id() {
      return "test";
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
