package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardIdentifierRules;
import io.skis.dialect.StandardSqlRenderer;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.JdbcCodecs;
import io.skis.mapping.PropertyRuntime;
import io.skis.mapping.RowReadContext;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.Identifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

class ProjectionQueryTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("pet_name", false));
  private static final PropertyMeta<Pet, BigDecimal> WEIGHT =
      new PropertyMeta<>(2, "weight", BigDecimal.class, ColumnMeta.of("weight", true));
  private static final PropertyMeta<Pet, Boolean> ADOPTED =
      new PropertyMeta<>(3, "adopted", Boolean.class, ColumnMeta.of("adopted", false));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "shelter", "pet"),
          List.of(ID, NAME, WEIGHT, ADOPTED),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);
  private static final PetTable TABLE = new PetTable();
  private static final Projection.Mapping<PetSummary> PET_SUMMARY_MAPPING =
      Projection.mapping(PetSummaryMapping.class);
  private static final Projection.Mapping<PetName> PET_NAME_MAPPING =
      Projection.mapping(PetNameMapping.class);
  private static final Projection.Mapping<PetWeight> PET_WEIGHT_MAPPING =
      Projection.mapping(PetWeightMapping.class);

  @Test
  void compilesAndDecodesOnlyTheSelectedScalarColumn() throws Exception {
    EntityPlanSet<Pet> plans = plans();

    CompiledQueryPlan<String, Object> plan =
        plans.projectionPlan(
            TABLE, Projection.scalar(TABLE.name()), TABLE.id().eq(7L));

    assertEquals(
        "SELECT \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" WHERE \"pet\".\"id\" = ?",
        plan.sql());
    assertEquals(
        "Mimi", plan.rowDecoder().decode(resultSet(Map.of(1, "Mimi")), RowReadContext.EMPTY));
  }

  @Test
  void mapsARecordThroughTheGeneratedMapperContractAndResultSetIndexes() throws Exception {
    Projection<Pet, PetSummary> projection =
        petSummaryProjection();

    CompiledQueryPlan<PetSummary, Object> plan =
        plans().projectionPlan(TABLE, projection, null);

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\", \"pet\".\"weight\" FROM \"shelter\".\"pet\"",
        plan.sql());
    assertEquals(
        new PetSummary(7L, "Mimi", new BigDecimal("12.50")),
        plan.rowDecoder()
            .decode(
                resultSet(
                    Map.of(
                        1, 7L,
                        2, "Mimi",
                        3, new BigDecimal("12.50"))),
                RowReadContext.EMPTY));
  }

  @Test
  void rejectsMismatchedScalarTableExpressionsAndNullRequiredValues() {
    PetTable alias = TABLE.as("p");

    assertThrows(
        QueryValidationException.class,
        () -> plans().projectionPlan(TABLE, Projection.scalar(alias.name()), null));
    assertThrows(QueryValidationException.class, () -> Projection.scalar(TABLE.weight()));

    CompiledQueryPlan<String, Object> plan =
        plans().projectionPlan(TABLE, Projection.scalar(TABLE.name()), null);
    assertThrows(
        SQLException.class,
        () -> plan.rowDecoder().decode(resultSetWithNull(1), RowReadContext.EMPTY));
  }

  @Test
  void rejectsAProjectionPredicateFromAnotherAliasOfTheSameEntity() {
    PetTable alias = TABLE.as("p");

    assertThrows(
        QueryValidationException.class,
        () ->
            plans()
                .projectionPlan(TABLE, petSummaryProjection(), alias.id().eq(7L)));
  }

  @Test
  void resolvesARegisteredProjectionByItsUserResultType() {
    Projection<Pet, PetName> projection = petNameProjection();
    ProjectionRegistry registry = ProjectionRegistry.of(List.of(projection));

    assertSame(projection, registry.require(TABLE, PetName.class));
    assertEquals(1, registry.size());
    assertThrows(
        QueryValidationException.class,
        () -> registry.require(TABLE, PetSummary.class));
  }

  @Test
  void mapsANullableColumnToANonNullUserProjection() throws Exception {
    Projection<Pet, PetWeight> projection = petWeightProjection();

    CompiledQueryPlan<PetWeight, Object> plan =
        plans().projectionPlan(TABLE, projection, null);

    assertEquals(
        new PetWeight(null),
        plan.rowDecoder().decode(resultSetWithNull(1), RowReadContext.EMPTY));
  }

  @Test
  void reusesValueIndependentProjectionPlansAcrossQueryValues() {
    ProjectionPlanCache cache = new ProjectionPlanCache(8);
    EntityPlanSet<Pet> plans =
        new EntityPlanSet<>(model(), new QueryPlanCompiler(TestDialect.INSTANCE), cache);
    Projection<Pet, PetSummary> firstProjection =
        petSummaryProjection();
    Projection<Pet, PetSummary> secondProjection =
        petSummaryProjection();
    QueryPredicate<Pet> firstPredicate = TABLE.id().eq(7L);
    QueryPredicate<Pet> secondPredicate = TABLE.id().eq(8L);

    CompiledQueryPlan<PetSummary, Object> first =
        plans.projectionPlan(TABLE, firstProjection, firstPredicate);
    CompiledQueryPlan<PetSummary, Object> second =
        plans.projectionPlan(TABLE, secondProjection, secondPredicate);

    assertSame(first, second);
    assertEquals(7L, plans.argument(firstPredicate));
    assertEquals(8L, plans.argument(secondPredicate));
    assertEquals(1, cache.size());
    assertEquals(
        new QueryPlanCacheStatistics(1, 1, 0, 0, 1, 8), cache.statistics());
  }

  @Test
  void boundsAndEvictsSharedProjectionPlans() {
    ProjectionPlanCache cache = new ProjectionPlanCache(1);
    EntityPlanSet<Pet> plans =
        new EntityPlanSet<>(model(), new QueryPlanCompiler(TestDialect.INSTANCE), cache);
    Projection<Pet, PetSummary> summary =
        petSummaryProjection();
    Projection<Pet, PetWeight> weight = petWeightProjection();

    CompiledQueryPlan<PetSummary, Object> original =
        plans.projectionPlan(TABLE, summary, null);
    plans.projectionPlan(TABLE, weight, null);
    CompiledQueryPlan<PetSummary, Object> recompiled =
        plans.projectionPlan(TABLE, summary, null);

    assertNotSame(original, recompiled);
    assertEquals(1, cache.size());
    assertEquals(2, cache.statistics().evictionCount());
  }

  @Test
  void keepsDifferentTypedMappingTokensIsolatedEvenWhenTheyNameTheSameMapperClass()
      throws Exception {
    Projection.Mapping<PetText> textMapping = Projection.mapping(SharedMapping.class);
    Projection.Mapping<PetLabel> labelMapping = Projection.mapping(SharedMapping.class);
    Projection<Pet, PetText> text =
        Projection.generated(
            PetText.class,
            PET,
            textMapping,
            List.of(NAME),
            readers -> {
              Projection.ValueReader<String> value = readers.reader(0, NAME);
              return (resultSet, context) -> new PetText(value.read(resultSet, context));
            });
    Projection<Pet, PetLabel> label =
        Projection.generated(
            PetLabel.class,
            PET,
            labelMapping,
            List.of(NAME),
            readers -> {
              Projection.ValueReader<String> value = readers.reader(0, NAME);
              return (resultSet, context) -> new PetLabel(value.read(resultSet, context));
            });
    EntityPlanSet<Pet> plans = plans();

    CompiledQueryPlan<PetText, Object> textPlan = plans.projectionPlan(TABLE, text, null);
    CompiledQueryPlan<PetLabel, Object> labelPlan = plans.projectionPlan(TABLE, label, null);

    assertEquals(
        new PetText("Mimi"),
        textPlan.rowDecoder().decode(resultSet(Map.of(1, "Mimi")), RowReadContext.EMPTY));
    assertEquals(
        new PetLabel("Mimi"),
        labelPlan.rowDecoder().decode(resultSet(Map.of(1, "Mimi")), RowReadContext.EMPTY));
  }

  @Test
  void expiresAndExplicitlyInvalidatesProjectionPlansWithObservableStatistics() {
    AtomicLong ticker = new AtomicLong();
    ProjectionPlanCache cache =
        new ProjectionPlanCache(2, Duration.ofNanos(10), ticker::get);
    EntityPlanSet<Pet> plans =
        new EntityPlanSet<>(model(), new QueryPlanCompiler(TestDialect.INSTANCE), cache);
    Projection<Pet, PetSummary> summary =
        petSummaryProjection();

    CompiledQueryPlan<PetSummary, Object> first =
        plans.projectionPlan(TABLE, summary, null);
    ticker.set(11);
    CompiledQueryPlan<PetSummary, Object> expired =
        plans.projectionPlan(TABLE, summary, null);

    assertNotSame(first, expired);
    assertEquals(new QueryPlanCacheStatistics(0, 2, 1, 0, 1, 2), cache.statistics());
    assertEquals(1, cache.invalidate(PET));
    assertEquals(new QueryPlanCacheStatistics(0, 2, 1, 1, 0, 2), cache.statistics());

    plans.projectionPlan(TABLE, summary, null);
    cache.clear();
    assertEquals(new QueryPlanCacheStatistics(0, 3, 1, 2, 0, 2), cache.statistics());
  }

  @Test
  void rejectsInvalidProjectionPlanCacheConfiguration() {
    assertThrows(IllegalArgumentException.class, () -> new ProjectionPlanCache(0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectionPlanCache(1, Duration.ZERO));
  }

  private static EntityPlanSet<Pet> plans() {
    return new EntityPlanSet<>(model(), new QueryPlanCompiler(TestDialect.INSTANCE));
  }

  private static Projection<Pet, PetSummary> petSummaryProjection() {
    return Projection.generated(
        PetSummary.class,
        PET,
        PET_SUMMARY_MAPPING,
        List.of(ID, NAME, WEIGHT),
        readers -> {
          Projection.ValueReader<Long> idReader = readers.reader(0, ID);
          Projection.ValueReader<String> nameReader = readers.reader(1, NAME);
          Projection.ValueReader<BigDecimal> weightReader = readers.reader(2, WEIGHT);
          return (resultSet, context) ->
              new PetSummary(
                  idReader.read(resultSet, context),
                  nameReader.read(resultSet, context),
                  weightReader.read(resultSet, context));
        });
  }

  private static Projection<Pet, PetName> petNameProjection() {
    return Projection.generated(
        PetName.class,
        PET,
        PET_NAME_MAPPING,
        List.of(ID, NAME),
        readers -> {
          Projection.ValueReader<Long> idReader = readers.reader(0, ID);
          Projection.ValueReader<String> nameReader = readers.reader(1, NAME);
          return (resultSet, context) ->
              new PetName(
                  idReader.read(resultSet, context), nameReader.read(resultSet, context));
        });
  }

  private static Projection<Pet, PetWeight> petWeightProjection() {
    return Projection.generated(
        PetWeight.class,
        PET,
        PET_WEIGHT_MAPPING,
        List.of(WEIGHT),
        readers -> {
          Projection.ValueReader<BigDecimal> weightReader = readers.reader(0, WEIGHT);
          return (resultSet, context) ->
              new PetWeight(weightReader.read(resultSet, context));
        });
  }

  private static EntityRuntimeModel<Pet> model() {
    return new EntityRuntimeModel<>(
        PET,
        layout ->
            (resultSet, context) ->
                new Pet(1L, "unused", BigDecimal.ONE, false),
        List.of(
            new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(NAME, JdbcCodecs.STRING),
            new PropertyRuntime<>(WEIGHT, JdbcCodecs.BIG_DECIMAL),
            new PropertyRuntime<>(ADOPTED, JdbcCodecs.BOOLEAN)));
  }

  private static ResultSet resultSet(Map<Integer, Object> values) {
    int[] lastIndex = new int[1];
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (ignored, method, arguments) -> {
              String name = method.getName();
              if (name.equals("wasNull")) {
                return values.get(lastIndex[0]) == null;
              }
              if (arguments != null
                  && arguments.length == 1
                  && arguments[0] instanceof Integer index) {
                lastIndex[0] = index;
                Object value = values.get(index);
                return switch (name) {
                  case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                  case "getString", "getBigDecimal", "getObject" -> value;
                  case "getBoolean" -> value != null && (Boolean) value;
                  default -> defaultValue(method.getReturnType());
                };
              }
              return defaultValue(method.getReturnType());
            });
  }

  private static ResultSet resultSetWithNull(int index) {
    return resultSet(java.util.Collections.singletonMap(index, null));
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
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    throw new AssertionError(type);
  }

  private record Pet(Long id, String name, BigDecimal weight, Boolean adopted) {}

  private record PetSummary(Long id, String name, BigDecimal weight) {}

  private record PetName(Long id, String name) {}

  private record PetWeight(@Nullable BigDecimal weight) {}

  private record PetText(String value) {}

  private record PetLabel(String value) {}

  private static final class PetSummaryMapping {

    private PetSummaryMapping() {}
  }

  private static final class PetNameMapping {

    private PetNameMapping() {}
  }

  private static final class PetWeightMapping {

    private PetWeightMapping() {}
  }

  private static final class SharedMapping {

    private SharedMapping() {}
  }

  private static final class PetTable extends QueryTable<Pet> {

    private final QueryColumn<Pet, Long> id = queryColumn(ID);
    private final QueryColumn<Pet, String> name = queryColumn(NAME);
    private final QueryColumn<Pet, BigDecimal> weight = queryColumn(WEIGHT);

    private PetTable() {
      super(PET);
    }

    private PetTable(Identifier alias) {
      super(PET, alias);
    }

    private QueryColumn<Pet, Long> id() {
      return id;
    }

    private QueryColumn<Pet, String> name() {
      return name;
    }

    private QueryColumn<Pet, BigDecimal> weight() {
      return weight;
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
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
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
