package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.skis.mapping.JdbcReadContext;
import io.skis.mapping.JdbcTypeCodec;
import io.skis.mapping.JdbcWriteContext;
import io.skis.mapping.PropertyRuntime;
import io.skis.mapping.RowReadContext;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DerivedRelationDslTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("pet_name", true));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "shelter", "pet"),
          List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);

  @Test
  void validatesExplicitOrderedShapeHandleMembershipAndAliasReuse() {
    PetTable inner = new PetTable().as("inner_pet");
    NonNullSelectDescription<Pet> description = Sql.selectFrom(inner);
    NonNullDerivedOutput<Long> id = Sql.output(inner.id(), "pet_id");
    DerivedOutput<String> name = Sql.output(inner.name(), "display_name");
    DerivedRelation first = Sql.derived(description, "first_pet", id, name);
    DerivedRelation second = first.as("second_pet");

    assertEquals(List.of(id, name), first.outputs());
    assertEquals(Nullability.NON_NULL, id.nullability());
    assertEquals(Nullability.NULLABLE, name.nullability());
    assertNotSame(first.reference(), second.reference());
    assertEquals(first.outputs(), second.outputs());

    NonNullSelectable<Long> firstId = first.column(id);
    NonNullSelectable<Long> secondId = second.column(id);
    NonNullSingleColumnSelect<Long> combined =
        Sql.select(firstId).from(first).crossJoin(second);
    CompiledQueryStructure structure = combined.state().structure();
    DerivedRelationSource root =
        assertInstanceOf(DerivedRelationSource.class, structure.fromClause().root());
    DerivedRelationSource joined =
        assertInstanceOf(
            DerivedRelationSource.class,
            structure.fromClause().joins().getFirst().right());
    assertSame(first.reference(), root.reference());
    assertSame(second.reference(), joined.reference());
    assertSame(
        first.reference(),
        assertInstanceOf(DerivedColumnExpression.class, structure.selections().getFirst())
            .relation());
    SemanticValidator.validateComplete(
        new SelectStatement(structure.selections(), structure.fromClause()));
    assertNotSame(firstId.expression(), secondId.expression());

    NonNullDerivedOutput<Long> copiedHandle = Sql.output(inner.id(), "pet_id");
    QueryValidationException membershipFailure =
        assertThrows(QueryValidationException.class, () -> first.column(copiedHandle));
    assertTrue(membershipFailure.getMessage().contains("handle identity"));

    assertThrows(
        QueryValidationException.class,
        () -> Sql.derived(description, "missing_output", id));
    assertThrows(
        QueryValidationException.class,
        () -> Sql.derived(description, "wrong_order", name, id));
    DerivedOutput<String> duplicateAlias = Sql.output(inner.name(), "pet_id");
    assertThrows(
        QueryValidationException.class,
        () -> Sql.derived(description, "duplicate_output", id, duplicateAlias));
    assertThrows(IllegalArgumentException.class, () -> Sql.output(inner.id(), ""));

    NonNullSingleColumnSelect<Long> duplicateRelationAlias =
        Sql.select(firstId).from(first).crossJoin(first.as("first_pet"));
    assertThrows(QueryValidationException.class, duplicateRelationAlias.state()::structure);
  }

  @Test
  void matchesRebuiltScalarOutputsByLogicalParameterIdentity() {
    PetTable scalarSource = new PetTable().as("scalar_pet");
    PetTable outer = new PetTable().as("outer_pet");
    QueryParameter<Long> shared = Sql.parameter(Long.class, "shared");
    NonNullSingleColumnSelect<Long> child =
        Sql.select(scalarSource.id())
            .from(scalarSource)
            .where(scalarSource.id().eq(shared));
    Selectable<Long> selectedScalar = Sql.scalar(child);
    Selectable<Long> rebuiltScalar = Sql.scalar(child);
    DerivedOutput<Long> output = Sql.output(rebuiltScalar, "scalar_id");
    SingleColumnSelect<Long> description = Sql.select(selectedScalar).from(outer);

    assertNotSame(selectedScalar, rebuiltScalar);
    DerivedRelation accepted = Sql.derived(description, "scalar_values", output);
    assertSame(output, accepted.outputs().getFirst());
    assertEquals(Nullability.NULLABLE, accepted.column(output).nullability());

    QueryParameter<Long> independent = Sql.parameter(Long.class, "shared");
    NonNullSingleColumnSelect<Long> independentChild =
        Sql.select(scalarSource.id())
            .from(scalarSource)
            .where(scalarSource.id().eq(independent));
    DerivedOutput<Long> wrong = Sql.output(Sql.scalar(independentChild), "scalar_id");

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () -> Sql.derived(description, "wrong_scalar_values", wrong));
    assertTrue(failure.getMessage().contains("not bound to SELECT expression"));
  }

  @Test
  void freezesEffectiveInnerJoinNullabilityBeforePublishingTheShape() {
    PetTable root = new PetTable().as("inner_root");
    PetTable optional = new PetTable().as("inner_optional");
    NonNullSingleColumnSelect<Long> leftJoined =
        Sql.select(optional.id())
            .from(root)
            .leftJoin(optional)
            .on(root.id().eq(optional.id()));
    NonNullDerivedOutput<Long> incorrectlyRequired =
        Sql.output(optional.id(), "optional_id");

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () -> Sql.derived(leftJoined, "optional_ids", incorrectlyRequired));
    assertTrue(failure.getMessage().contains("effectively nullable"));
    assertTrue(failure.getMessage().contains("Sql.outputNullable"));

    DerivedOutput<Long> nullable = Sql.outputNullable(optional.id(), "optional_id");
    DerivedRelation accepted = Sql.derived(leftJoined, "optional_ids", nullable);
    assertEquals(Nullability.NULLABLE, accepted.column(nullable).nullability());
  }

  @Test
  void rejectsAChildThatCapturesAnOuterPhysicalTable() {
    PetTable outer = new PetTable().as("outer_pet");
    PetTable inner = new PetTable().as("inner_pet");
    NonNullSingleColumnSelect<Long> invalid = Sql.select(outer.id()).from(inner);
    NonNullDerivedOutput<Long> exposed = Sql.output(outer.id(), "pet_id");

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () -> Sql.derived(invalid, "invalid_boundary", exposed));

    assertTrue(failure.getMessage().contains("unresolved outer reference"));
    assertTrue(failure.getMessage().contains("table references are matched by object identity"));
  }

  @Test
  void rejectsOuterAccessToAnUnpublishedInnerPhysicalColumn() {
    PetTable inner = new PetTable().as("inner_pet");
    NonNullDerivedOutput<Long> id = Sql.output(inner.id(), "pet_id");
    DerivedRelation relation =
        Sql.derived(Sql.select(inner.id()).from(inner), "published_pet", id);
    SingleColumnSelect<String> invalid = Sql.select(inner.name()).from(relation);
    CompiledQueryStructure structure = invalid.state().structure();

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SemanticValidator.validateComplete(
                    new SelectStatement(structure.selections(), structure.fromClause())));

    assertTrue(failure.getMessage().contains("unresolved outer reference"));
    assertTrue(failure.getMessage().contains("table references are matched by object identity"));

    QueryValidationException compilationFailure =
        assertThrows(
            QueryValidationException.class,
            () -> compile(invalid, QueryParameters.empty()));
    assertTrue(compilationFailure.getMessage().contains("not visible in the final query scope"));
  }

  @Test
  void propagatesNestedParametersAndPhysicalCodecThroughADerivedRoot() throws Exception {
    PetTable inner = new PetTable().as("inner_pet");
    QueryParameter<Long> minimum = Sql.parameter(Long.class, "minimum");
    NonNullSingleColumnSelect<Long> child =
        Sql.select(inner.id()).from(inner).where(inner.id().gt(minimum));
    NonNullDerivedOutput<Long> id = Sql.output(inner.id(), "pet_id");
    DerivedRelation relation = Sql.derived(child, "filtered_pets", id);
    NonNullSelectable<Long> derivedId = relation.column(id);
    QueryParameter<Long> maximum = Sql.parameter(Long.class, "maximum");
    NonNullSingleColumnSelect<Long> description =
        Sql.select(derivedId).from(relation).where(derivedId.lt(maximum));
    QueryParameters parameters =
        QueryParameters.builder().bind(minimum, 10L).bind(maximum, 50L).build();

    CompiledQueryStructure structure = description.state().structure();
    assertEquals(List.of(minimum, maximum), structure.parameterReferences());
    QueryCompilation<Long> compilation = compile(description, parameters);

    assertEquals(
        "SELECT \"filtered_pets\".\"pet_id\" FROM "
            + "(SELECT \"inner_pet\".\"id\" AS \"pet_id\" "
            + "FROM \"shelter\".\"pet\" AS \"inner_pet\" "
            + "WHERE \"inner_pet\".\"id\" > ?) AS \"filtered_pets\" "
            + "WHERE \"filtered_pets\".\"pet_id\" < ?",
        compilation.plan().sql());
    assertEquals(List.of(10L, 50L), ((QueryArguments) compilation.argument()).values());
    assertEquals(
        42L,
        compilation
            .plan()
            .rowDecoder()
            .decode(resultSet(Map.of(1, 42L)), RowReadContext.EMPTY));

    TableRuntimeScope scope = TableRuntimeScope.resolve(registry(), structure.fromClause());
    ResolvedValueMapping<Long> mapping = ResolvedValueMapping.resolve(derivedId, scope);
    assertSame(JdbcCodecs.LONG, mapping.codec());
    assertEquals(0, mapping.sourceOccurrenceOrdinal());
  }

  @Test
  void propagatesACustomCodecThroughTwoDerivedLevelsWithoutMaterializingInnerResults()
      throws Exception {
    AtomicInteger codecReads = new AtomicInteger();
    AtomicInteger entityDecoderCreations = new AtomicInteger();
    AtomicInteger entityDecoderInvocations = new AtomicInteger();
    AtomicInteger projectionDecoderCreations = new AtomicInteger();
    JdbcTypeCodec<Long> customCodec =
        new JdbcTypeCodec<>() {
          @Override
          public @Nullable Long read(
              ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
            codecReads.incrementAndGet();
            return JdbcCodecs.LONG.read(resultSet, index, context);
          }

          @Override
          public void bind(
              PreparedStatement statement,
              int index,
              @Nullable Long value,
              JdbcWriteContext context)
              throws SQLException {
            JdbcCodecs.LONG.bind(statement, index, value, context);
          }
        };
    EntityRuntimeModel<Pet> model =
        new EntityRuntimeModel<>(
            PET,
            layout -> {
              entityDecoderCreations.incrementAndGet();
              return (resultSet, context) -> {
                entityDecoderInvocations.incrementAndGet();
                return new Pet(
                    customCodec.read(resultSet, layout.requireIndex(0), context),
                    JdbcCodecs.STRING.read(resultSet, layout.requireIndex(1), context));
              };
            },
            List.of(
                new PropertyRuntime<>(ID, customCodec),
                new PropertyRuntime<>(NAME, JdbcCodecs.STRING)));
    EntityRuntimeRegistry customRegistry = EntityRuntimeRegistry.of(List.of(model));
    assertEquals(1, entityDecoderCreations.get());
    assertEquals(0, entityDecoderInvocations.get());
    ProjectionMapping<InnerIdView> innerMapping =
        ProjectionMapping.generated(
            GeneratedModelAbi.CURRENT,
            InnerIdView.class,
            "derived-inner-id",
            List.of(
                new ProjectionMapping.Parameter(
                    0, "id", Long.class, Nullability.NON_NULL, 0)),
            readers -> {
              projectionDecoderCreations.incrementAndGet();
              ProjectionMapping.ValueReader<Long> id = readers.reader(0, Long.class);
              return (resultSet, context) -> new InnerIdView(id.read(resultSet, context));
            });
    assertEquals(0, projectionDecoderCreations.get());
    PetTable inner = new PetTable().as("inner_pet");
    NonNullDerivedOutput<Long> firstOutput = Sql.output(inner.id(), "pet_id");
    DerivedRelation first =
        Sql.derived(
            Sql.select(innerMapping.bind(inner.id())).from(inner),
            "first_level",
            firstOutput);
    NonNullSelectable<Long> firstColumn = first.column(firstOutput);
    NonNullSingleColumnSelect<Long> oneLevel = Sql.select(firstColumn).from(first);
    CompiledQueryStructure oneLevelStructure = oneLevel.state().structure();
    TableRuntimeScope oneLevelScope =
        TableRuntimeScope.resolve(customRegistry, oneLevelStructure.fromClause());

    assertSame(customCodec, ResolvedValueMapping.resolve(firstColumn, oneLevelScope).codec());

    NonNullDerivedOutput<Long> secondOutput = Sql.output(firstColumn, "pet_id");
    DerivedRelation second = Sql.derived(oneLevel, "second_level", secondOutput);
    NonNullSelectable<Long> secondColumn = second.column(secondOutput);
    NonNullSingleColumnSelect<Long> twoLevels = Sql.select(secondColumn).from(second);
    CompiledQueryStructure twoLevelStructure = twoLevels.state().structure();
    TableRuntimeScope twoLevelScope =
        TableRuntimeScope.resolve(customRegistry, twoLevelStructure.fromClause());

    assertSame(customCodec, ResolvedValueMapping.resolve(secondColumn, twoLevelScope).codec());
    QueryCompilation<Long> compilation =
        compile(twoLevels, QueryParameters.empty(), customRegistry);
    assertEquals(
        42L,
        compilation
            .plan()
            .rowDecoder()
            .decode(resultSet(Map.of(1, 42L)), RowReadContext.EMPTY));
    assertEquals(1, codecReads.get());
    assertEquals(1, entityDecoderCreations.get());
    assertEquals(0, entityDecoderInvocations.get());
    assertEquals(0, projectionDecoderCreations.get());
  }

  @Test
  void outerJoinNullExtendsANonNullDerivedOutputAndPreservesItsCodec() throws Exception {
    PetTable inner = new PetTable().as("inner_pet");
    NonNullSingleColumnSelect<Long> child = Sql.select(inner.id()).from(inner);
    NonNullDerivedOutput<Long> id = Sql.output(inner.id(), "row_count");
    DerivedRelation relation = Sql.derived(child, "derived_counts", id);
    NonNullSelectable<Long> derivedValue = relation.column(id);
    PetTable outer = new PetTable().as("outer_pet");
    SingleColumnSelect<Long> nullableDescription =
        Sql.selectNullable(derivedValue)
            .from(outer)
            .leftJoin(relation)
            .on(outer.id().eq(derivedValue));

    CompiledQueryStructure structure = nullableDescription.state().structure();
    TableRuntimeScope scope = TableRuntimeScope.resolve(registry(), structure.fromClause());
    ResolvedValueMapping<Long> mapping = ResolvedValueMapping.resolve(derivedValue, scope);

    assertEquals(Nullability.NULLABLE, mapping.effectiveNullability());
    assertSame(JdbcCodecs.LONG, mapping.codec());
    assertEquals(1, mapping.sourceOccurrenceOrdinal());
    QueryCompilation<Long> compilation = compile(nullableDescription, QueryParameters.empty());
    assertNull(
        compilation
            .plan()
            .rowDecoder()
            .decode(resultSet(Map.of()), RowReadContext.EMPTY));

    NonNullSingleColumnSelect<Long> incorrectlyRequired =
        Sql.select(derivedValue)
            .from(outer)
            .leftJoin(relation)
            .on(outer.id().eq(derivedValue));
    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () -> compile(incorrectlyRequired, QueryParameters.empty()));
    assertTrue(failure.getMessage().contains("effectively nullable"));
  }

  private static <R> QueryCompilation<R> compile(
      SelectDescription<R> description, QueryParameters parameters) {
    return compile(description, parameters, registry());
  }

  private static <R> QueryCompilation<R> compile(
      SelectDescription<R> description,
      QueryParameters parameters,
      EntityRuntimeRegistry registry) {
    SelectQueryState<R> state = description.state();
    CompiledQueryStructure structure = state.structure();
    return QueryRuntime.compile(registry, TestDialect.INSTANCE)
        .compiler()
        .compileSelection(
            state.selected(),
            structure,
            state.orderBy(),
            state.distinct(),
            QueryPagination.None.INSTANCE,
            List.of(),
            structure.arguments(parameters));
  }

  private static EntityRuntimeRegistry registry() {
    return EntityRuntimeRegistry.of(List.of(petModel()));
  }

  private static EntityRuntimeModel<Pet> petModel() {
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

  private record Pet(Long id, String name) {}

  private record InnerIdView(Long id) {}

  private static final class PetTable extends QueryTable<Pet> {

    private final NonNullQueryColumn<Pet, Long> id = nonNullQueryColumn(ID);
    private final NullableQueryColumn<Pet, String> name = nullableQueryColumn(NAME);

    private PetTable() {
      super(PET);
    }

    private PetTable(Identifier alias) {
      super(PET, alias);
    }

    private NonNullQueryColumn<Pet, Long> id() {
      return id;
    }

    private NullableQueryColumn<Pet, String> name() {
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

  private enum TestDialect implements Dialect {
    INSTANCE;

    private final DialectCapabilities capabilities =
        DialectCapabilities.of(
            DialectFeature.SCHEMA_QUALIFIED_TABLES,
            DialectFeature.INNER_JOIN,
            DialectFeature.LEFT_JOIN,
            DialectFeature.RIGHT_JOIN,
            DialectFeature.FULL_JOIN,
            DialectFeature.CROSS_JOIN,
            DialectFeature.DERIVED_TABLE);
    private final SqlRenderer renderer =
        new StandardSqlRenderer(id(), identifierRules(), capabilities);

    @Override
    public String id() {
      return "derived-test";
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
