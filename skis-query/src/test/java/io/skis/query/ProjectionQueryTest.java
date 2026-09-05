package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import io.skis.mapping.PropertyRuntime;
import io.skis.mapping.RowReadContext;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.Identifier;
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
                    OWNER_TABLE,
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
                    OWNER_TABLE,
                    PET_TABLE.ownerId().eq(OWNER_TABLE.id())),
                new QueryJoin(
                    JoinType.INNER, reviewer, OWNER_TABLE.id().eq(reviewer.id()))));

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
                            OWNER_TABLE,
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
                    OWNER_TABLE,
                    PET_TABLE.ownerId().eq(OWNER_TABLE.id()))));

    assertEquals(
        new PetOwnerView(7L, null),
        compilation
            .plan()
            .rowDecoder()
            .decode(resultSet(java.util.Collections.singletonMap(1, 7L)), RowReadContext.EMPTY));
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
    EntityPlanSet<Pet> plans = catalog.require(PET);
    return plans
        .compiler()
        .compileSelection(
            plans.model(),
            PET_TABLE,
            SelectedResult.projection(selection),
            joins,
            null,
            List.of(),
            false,
            QueryPagination.None.INSTANCE,
            List.of());
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

  private record WrongTypeView(String value) {}

  private static final class PetTable extends QueryTable<Pet> {

    private final NonNullQueryColumn<Pet, Long> id = nonNullQueryColumn(PET_ID);
    private final NonNullQueryColumn<Pet, Long> ownerId = nonNullQueryColumn(PET_OWNER_ID);

    private PetTable() {
      super(PET);
    }

    private PetTable(Identifier alias) {
      super(PET, alias);
    }

    private NonNullQueryColumn<Pet, Long> id() {
      return id;
    }

    private NonNullQueryColumn<Pet, Long> ownerId() {
      return ownerId;
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
    private final NonNullQueryColumn<Owner, String> name = nonNullQueryColumn(OWNER_NAME);

    private OwnerTable() {
      super(OWNER);
    }

    private OwnerTable(Identifier alias) {
      super(OWNER, alias);
    }

    private NonNullQueryColumn<Owner, Long> id() {
      return id;
    }

    private NonNullQueryColumn<Owner, String> name() {
      return name;
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

  private enum TestDialect implements Dialect {
    INSTANCE;

    private final DialectCapabilities capabilities =
        DialectCapabilities.of(
            DialectFeature.SCHEMA_QUALIFIED_TABLES,
            DialectFeature.INNER_JOIN,
            DialectFeature.LEFT_JOIN);
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
