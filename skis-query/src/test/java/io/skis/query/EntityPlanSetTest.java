package io.skis.query;

import static io.skis.query.QueryTestSupport.argument;
import static io.skis.query.QueryTestSupport.arguments;
import static io.skis.query.QueryTestSupport.selectPlan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.ExecutionOptions;
import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.StatementPurpose;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.DialectLowering;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.RenderedSql;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.jdbc.internal.CompiledQueryPlan;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.QueryExecutionException;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.LogicalOperator;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.StatementAst;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class EntityPlanSetTest {

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
  private static final PropertyMeta<OtherPet, Long> OTHER_ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final EntityMeta<OtherPet> OTHER_PET =
      EntityMeta.simple(
          OtherPet.class,
          new TableMeta("", "shelter", "other_pet"),
          List.of(OTHER_ID),
          new PrimaryKeyMeta<>(List.of(OTHER_ID)),
          false);
  private static final PetTable TABLE = new PetTable();
  private static final OtherPetTable OTHER_TABLE = new OtherPetTable();

  @Test
  void queryTableCachesCanonicalColumnWrappers() {
    assertSame(TABLE.id(), TABLE.internalColumn(ID));
    assertSame(TABLE.name(), TABLE.internalColumn(NAME));
    assertSame(SelectableSupport.expression(TABLE.id()), TABLE.selections().get(0));
    assertSame(SelectableSupport.expression(TABLE.name()), TABLE.selections().get(1));
  }

  @Test
  void queryCompilerValidatesCompleteSemanticsBeforeDialectOrRenderer() {
    List<String> laterPhases = new ArrayList<>();
    Dialect nonValidatingDialect =
        new Dialect() {
          private final DialectCapabilities capabilities =
              DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
          private final SqlRenderer renderer =
              input -> {
                laterPhases.add("renderer");
                return "SELECT 1";
              };

          @Override
          public String id() {
            return "non-validating-test";
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

          @Override
          public void validate(ResolvedStatement resolved) {
            laterPhases.add("dialect");
          }
        };
    EntityRuntimeModel<Pet> runtimeModel = model();
    QueryPlanCompiler compiler =
        QueryTestSupport.compiler(
            EntityRuntimeRegistry.of(List.of(runtimeModel)), nonValidatingDialect);
    CompiledQueryStructure structure =
        QueryTestSupport.compile(TABLE, List.of(), TABLE.id().eq(OTHER_TABLE.id()));

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () ->
                compiler.compileQuery(
                    runtimeModel, TABLE, structure, null, StatementPurpose.PRIMARY));

    assertEquals(SkisErrorCode.QUERY_COMPILED_SHAPE, failure.code());
    assertTrue(failure.getCause().getMessage().contains("unresolved outer reference"));
    assertEquals(List.of(), laterPhases);
  }

  @Test
  void prewarmsFindByIdAndReusesOneBoundedPlanPerProperty() {
    EntityPlanSet<Pet> plans = plans();

    CompiledQueryPlan<Pet, Object> findById = plans.findByIdPlan();
    CompiledQueryPlan<Pet, Object> firstName = selectPlan(plans, TABLE, TABLE.name().eq("Mimi"));
    CompiledQueryPlan<Pet, Object> secondName = selectPlan(plans, TABLE, TABLE.name().eq("Fifi"));

    assertSame(ID, plans.findByIdProperty());
    assertSame(findById, plans.findByIdPlan());
    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" WHERE \"pet\".\"id\" = ?",
        findById.sql());
    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" WHERE \"pet\".\"pet_name\" = ?",
        firstName.sql());
    assertSame(firstName, secondName);
  }

  @Test
  void keepsValuesOutsidePlansAndAst() {
    EntityPlanSet<Pet> plans = plans();
    QueryCondition mimi = TABLE.name().eq("Mimi");
    QueryCondition fifi = TABLE.name().eq("Fifi");

    assertSame(selectPlan(plans, TABLE, mimi), selectPlan(plans, TABLE, fifi));
    assertEquals(List.of("Mimi"), (QueryArguments) argument(TABLE, mimi));
    assertEquals(List.of("Fifi"), (QueryArguments) argument(TABLE, fifi));
    QueryCondition firstComplex = TABLE.name().like("Mi%").and(TABLE.id().between(1L, 9L));
    QueryCondition secondComplex = TABLE.name().like("Mo%").and(TABLE.id().between(2L, 10L));
    CompiledQueryStructure firstCompiled = QueryTestSupport.compile(TABLE, List.of(), firstComplex);
    CompiledQueryStructure secondCompiled =
        QueryTestSupport.compile(TABLE, List.of(), secondComplex);
    assertEquals(firstCompiled.where(), secondCompiled.where());
    assertEquals(firstCompiled, secondCompiled);
    assertEquals(firstCompiled.hashCode(), secondCompiled.hashCode());
    assertEquals(List.of("Mi%", 1L, 9L), arguments(firstCompiled));
    assertEquals(List.of("Mo%", 2L, 10L), arguments(secondCompiled));
    assertSame(NoParameters.INSTANCE, argument(TABLE, null));
    assertEquals(0, selectPlan(plans, TABLE, null).parameterCount());
  }

  @Test
  void compilesGroupedPredicatesWithStableParameterEncounterOrder() {
    EntityPlanSet<Pet> plans = plans();
    QueryCondition predicate =
        TABLE.name().isNull().or(TABLE.name().like("Mi%")).and(TABLE.id().ge(1L));

    CompiledQueryPlan<Pet, Object> plan = selectPlan(plans, TABLE, predicate);

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" "
            + "WHERE (\"pet\".\"pet_name\" IS NULL OR \"pet\".\"pet_name\" LIKE ?) "
            + "AND \"pet\".\"id\" >= ?",
        plan.sql());
    assertEquals(List.of("Mi%", 1L), (QueryArguments) argument(TABLE, predicate));
  }

  @Test
  void bindsComplexPredicateArgumentsInPlaceholderEncounterOrder() throws Exception {
    EntityPlanSet<Pet> plans = plans();
    QueryCondition predicate = TABLE.name().like("Mi%").and(TABLE.id().ge(1L));
    CompiledQueryPlan<Pet, Object> plan = selectPlan(plans, TABLE, predicate);
    List<List<Object>> bindings = new ArrayList<>();
    PreparedStatement statement =
        (PreparedStatement)
            Proxy.newProxyInstance(
                EntityPlanSetTest.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (ignored, method, arguments) -> {
                  if (method.getName().equals("setString") || method.getName().equals("setLong")) {
                    bindings.add(List.of(method.getName(), arguments[0], arguments[1]));
                  }
                  return null;
                });

    int nextIndex =
        plan.parameterBinder()
            .bind(statement, 3, argument(TABLE, predicate), JdbcWriteContext.EMPTY);

    assertEquals(5, nextIndex);
    assertEquals(List.of(List.of("setString", 3, "Mi%"), List.of("setLong", 4, 1L)), bindings);
  }

  @Test
  void oneQueryParameterReferenceCanProduceMultipleJdbcPositions() throws Exception {
    EntityPlanSet<Pet> plans = plans();
    QueryCondition oneReference = TABLE.name().eq("Mimi");
    QueryCondition repeated = oneReference.and(oneReference);
    CompiledQueryStructure compiled = QueryTestSupport.compile(TABLE, List.of(), repeated);
    CompiledQueryPlan<Pet, Object> plan = selectPlan(plans, TABLE, repeated);
    List<List<Object>> bindings = new ArrayList<>();
    PreparedStatement statement =
        (PreparedStatement)
            Proxy.newProxyInstance(
                EntityPlanSetTest.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (ignored, method, arguments) -> {
                  if (method.getName().equals("setString")) {
                    bindings.add(List.of(arguments[0], arguments[1]));
                  }
                  return null;
                });

    int nextIndex =
        plan.parameterBinder()
            .bind(statement, 4, argument(TABLE, repeated), JdbcWriteContext.EMPTY);

    assertEquals(1, compiled.parameterReferences().size());
    assertEquals(List.of("Mimi"), arguments(compiled));
    assertEquals(
        List.of(0, 0),
        plan.renderedSql().parameters().stream().map(slot -> slot.ordinal()).toList());
    assertEquals(6, nextIndex);
    assertEquals(List.of(List.of(4, "Mimi"), List.of(5, "Mimi")), bindings);
  }

  @Test
  void repeatedQueryBlockOccurrencesRelocateTheSameReferenceToDistinctLogicalSlots() {
    StatementParameterLayout layout = new StatementParameterLayout();
    QueryParameter<Long> parameter = Sql.parameter(Long.class, "id");
    StatementParameterLayout.QueryBlock firstBlock = layout.newQueryBlock();
    StatementParameterLayout.QueryBlock secondBlock = layout.newQueryBlock();

    ParameterSlot<Long> firstUse = firstBlock.parameter(TABLE.id(), parameter);
    ParameterSlot<Long> repeatedInFirstBlock = firstBlock.parameter(TABLE.id(), parameter);
    ParameterSlot<Long> secondOccurrence = secondBlock.parameter(TABLE.id(), parameter);

    assertSame(firstUse, repeatedInFirstBlock);
    assertEquals(0, firstUse.ordinal());
    assertEquals(1, secondOccurrence.ordinal());
    assertEquals(List.of(parameter, parameter), layout.parameterReferences());
    assertEquals(
        List.of(9L, 9L), QueryParameters.of(parameter, 9L).valuesFor(layout.parameterReferences()));
    assertThrows(
        QueryValidationException.class, () -> registerWithWrongJavaType(firstBlock, parameter));
    assertThrows(
        QueryValidationException.class, () -> firstBlock.parameter(OTHER_TABLE.id(), parameter));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void registerWithWrongJavaType(
      StatementParameterLayout.QueryBlock block, QueryParameter<?> parameter) {
    block.parameter((Selectable) TABLE.name(), (QueryParameter) parameter);
  }

  @Test
  void nullableQueryParametersBindNullThroughTheResolvedColumnCodec() throws Exception {
    EntityRuntimeModel<Pet> runtimeModel = model();
    QueryPlanCompiler compiler =
        QueryTestSupport.compiler(
            EntityRuntimeRegistry.of(List.of(runtimeModel)), TestDialect.INSTANCE);
    QueryParameter<Long> parameter = Sql.parameter(Long.class, "id");
    StatementParameterLayout layout = new StatementParameterLayout();
    ParameterSlot<Long> slot = layout.newQueryBlock().parameter(TABLE.id(), parameter);
    QueryParameters parameters = QueryParameters.of(parameter, null);
    CompiledQueryStructure structure =
        new CompiledQueryStructure(
            FromClause.of(TABLE.tableExpression()),
            List.of(),
            new ComparisonPredicate<>(
                SelectableSupport.expression(TABLE.id()), ComparisonOperator.EQUAL, slot),
            List.of(),
            null,
            List.of(),
            layout.parameterSources(),
            layout.parameterReferences(),
            layout.parameterSlots(),
            parameters,
            null);
    CompiledQueryPlan<Pet, Object> plan =
        compiler.compileQuery(runtimeModel, TABLE, structure, null, StatementPurpose.PRIMARY);
    AtomicReference<List<Object>> binding = new AtomicReference<>();
    PreparedStatement statement =
        (PreparedStatement)
            Proxy.newProxyInstance(
                EntityPlanSetTest.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (ignored, method, arguments) -> {
                  if (method.getName().equals("setNull")) {
                    binding.set(List.of(method.getName(), arguments[0], arguments[1]));
                  }
                  return null;
                });

    int nextIndex =
        plan.parameterBinder()
            .bind(statement, 1, arguments(structure).planArgument(), JdbcWriteContext.EMPTY);

    assertEquals(2, nextIndex);
    assertEquals(List.of("setNull", 1, Types.BIGINT), binding.get());
  }

  @Test
  void followsLoweredParameterOrderWhenDialectReordersPredicates() throws Exception {
    EntityRuntimeModel<Pet> runtimeModel = model();
    EntityPlanSet<Pet> plans =
        new EntityPlanSet<>(
            runtimeModel,
            QueryTestSupport.compiler(
                EntityRuntimeRegistry.of(List.of(runtimeModel)),
                ReorderedParameterDialect.INSTANCE));
    QueryCondition predicate = TABLE.name().like("Mi%").and(TABLE.id().ge(1L));
    CompiledQueryPlan<Pet, Object> plan = selectPlan(plans, TABLE, predicate);
    List<List<Object>> bindings = new ArrayList<>();
    PreparedStatement statement =
        (PreparedStatement)
            Proxy.newProxyInstance(
                EntityPlanSetTest.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (ignored, method, arguments) -> {
                  if (method.getName().equals("setString") || method.getName().equals("setLong")) {
                    bindings.add(List.of(method.getName(), arguments[0], arguments[1]));
                  }
                  return null;
                });

    int nextIndex =
        plan.parameterBinder()
            .bind(statement, 1, argument(TABLE, predicate), JdbcWriteContext.EMPTY);

    assertEquals(3, nextIndex);
    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" "
            + "WHERE \"pet\".\"id\" >= ? AND \"pet\".\"pet_name\" LIKE ?",
        plan.sql());
    assertEquals(List.of(List.of("setLong", 1, 1L), List.of("setString", 2, "Mi%")), bindings);
  }

  @Test
  void compilesEveryInitialComparisonAndValuePredicate() {
    EntityPlanSet<Pet> plans = plans();
    String prefix = "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" WHERE ";

    assertEquals(prefix + "\"pet\".\"id\" <> ?", selectPlan(plans, TABLE, TABLE.id().ne(1L)).sql());
    assertEquals(prefix + "\"pet\".\"id\" > ?", selectPlan(plans, TABLE, TABLE.id().gt(1L)).sql());
    assertEquals(prefix + "\"pet\".\"id\" >= ?", selectPlan(plans, TABLE, TABLE.id().ge(1L)).sql());
    assertEquals(prefix + "\"pet\".\"id\" < ?", selectPlan(plans, TABLE, TABLE.id().lt(1L)).sql());
    assertEquals(prefix + "\"pet\".\"id\" <= ?", selectPlan(plans, TABLE, TABLE.id().le(1L)).sql());
    assertEquals(
        prefix + "\"pet\".\"pet_name\" IS NOT NULL",
        selectPlan(plans, TABLE, TABLE.name().isNotNull()).sql());
    assertEquals(
        prefix + "\"pet\".\"id\" BETWEEN ? AND ?",
        selectPlan(plans, TABLE, TABLE.id().between(1L, 9L)).sql());
    assertEquals(
        prefix + "\"pet\".\"pet_name\" LIKE ?",
        selectPlan(plans, TABLE, TABLE.name().like("Mi%")).sql());
    assertEquals(
        prefix + "\"pet\".\"id\" IN (?, ?)",
        selectPlan(plans, TABLE, TABLE.id().in(List.of(1L, 2L))).sql());
    assertEquals(
        prefix + "\"pet\".\"id\" NOT IN (?, ?)",
        selectPlan(plans, TABLE, TABLE.id().notIn(List.of(1L, 2L))).sql());
    assertEquals(
        prefix + "NOT (\"pet\".\"id\" = ?)",
        selectPlan(plans, TABLE, TABLE.id().eq(1L).not()).sql());
  }

  @Test
  void compilesEmptyMembershipWithoutParameters() {
    EntityPlanSet<Pet> plans = plans();
    QueryCondition emptyIn = TABLE.id().in(List.of());
    QueryCondition emptyNotIn = TABLE.id().notIn(List.of());

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" WHERE FALSE",
        selectPlan(plans, TABLE, emptyIn).sql());
    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" WHERE TRUE",
        selectPlan(plans, TABLE, emptyNotIn).sql());
    assertSame(NoParameters.INSTANCE, argument(TABLE, emptyIn));
    assertSame(NoParameters.INSTANCE, argument(TABLE, emptyNotIn));
  }

  @Test
  void queryLevelAndOrReturnNewImmutableQueries() {
    QueryOperations operations =
        QueryRuntime.compile(EntityRuntimeRegistry.of(List.of(model())), TestDialect.INSTANCE)
            .bind(
                new JdbcExecutor(
                    new ConnectionProvider() {
                      @Override
                      public Connection acquire(ExecutionContext context) {
                        throw new AssertionError("query construction must not acquire JDBC");
                      }

                      @Override
                      public void release(Connection connection, ExecutionContext context) {}
                    }));
    SelectQuery<Pet> base = operations.selectFrom(TABLE);
    SelectQuery<Pet> filtered =
        base.where(TABLE.name().isNull())
            .and(TABLE.id().ge(1L))
            .and(TABLE.name().like("Mi%").or(TABLE.name().like("Mo%")));

    assertNotSame(base, filtered);
    assertThrows(QueryValidationException.class, () -> base.and(TABLE.id().eq(1L)));
    assertThrows(QueryValidationException.class, () -> filtered.where(TABLE.id().eq(2L)));
  }

  @Test
  void publishesOnePlanIdentityUnderConcurrentFirstUse() {
    EntityPlanSet<Pet> plans = plans();
    List<CompiledQueryPlan<Pet, Object>> observed =
        IntStream.range(0, 64)
            .parallel()
            .mapToObj(index -> selectPlan(plans, TABLE, TABLE.name().eq("pet-" + index)))
            .toList();

    CompiledQueryPlan<Pet, Object> published = observed.getFirst();
    observed.forEach(plan -> assertSame(published, plan));
  }

  @Test
  void rejectsAmbiguousNullAndPredicatesFromAnotherTable() {
    PetTable alias = TABLE.as("p");

    assertThrows(QueryValidationException.class, () -> TABLE.name().eq((String) null));
    assertThrows(
        QueryValidationException.class, () -> selectPlan(plans(), TABLE, alias.name().eq("Mimi")));
    assertThrows(
        QueryValidationException.class,
        () -> selectPlan(plans(), TABLE, TABLE.id().eq(1L).and(alias.name().isNotNull())));
  }

  @Test
  void rejectsNullFastPathArgumentBeforeAcquiringAConnection() {
    QueryOperations operations =
        QueryRuntime.compile(EntityRuntimeRegistry.of(List.of(model())), TestDialect.INSTANCE)
            .bind(
                new JdbcExecutor(
                    new ConnectionProvider() {
                      @Override
                      public Connection acquire(@NonNull ExecutionContext context) {
                        throw new AssertionError(
                            "null validation must happen before JDBC execution");
                      }

                      @Override
                      public void release(Connection connection, ExecutionContext context) {}
                    }));

    assertThrows(QueryValidationException.class, () -> operations.findById(TABLE, null));
  }

  @Test
  void propagatesImmutableOptionsFromFastPathAndFluentQueries() {
    AtomicReference<ExecutionOptions> observed = new AtomicReference<>();
    QueryOperations operations =
        QueryRuntime.compile(EntityRuntimeRegistry.of(List.of(model())), TestDialect.INSTANCE)
            .bind(
                new JdbcExecutor(
                    new ConnectionProvider() {
                      @Override
                      public Connection acquire(ExecutionContext context) throws SQLException {
                        observed.set(context.executionOptions());
                        throw new SQLException("stop before JDBC work", "08001");
                      }

                      @Override
                      public void release(Connection connection, ExecutionContext context) {}
                    }));
    ExecutionOptions options = ExecutionOptions.builder().fetchSize(64).build();

    assertThrows(QueryExecutionException.class, () -> operations.findById(TABLE, 1L, options));
    assertSame(options, observed.get());

    observed.set(null);
    SelectQuery<Pet> baseQuery = operations.selectFrom(TABLE);
    SelectQuery<Pet> configuredQuery = baseQuery.withOptions(options);
    assertThrows(QueryExecutionException.class, configuredQuery::fetchList);
    assertSame(options, observed.get());
    assertSame(baseQuery, baseQuery.withOptions(ExecutionOptions.NONE));
  }

  private static EntityPlanSet<Pet> plans() {
    EntityRuntimeModel<Pet> runtimeModel = model();
    return new EntityPlanSet<>(
        runtimeModel,
        QueryTestSupport.compiler(
            EntityRuntimeRegistry.of(List.of(runtimeModel)), TestDialect.INSTANCE));
  }

  private static EntityRuntimeModel<Pet> model() {
    return new EntityRuntimeModel<>(
        PET,
        layout -> (resultSet, context) -> new Pet(1L, "Mimi"),
        List.of(
            new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(NAME, JdbcCodecs.STRING)));
  }

  private record Pet(Long id, String name) {}

  private record OtherPet(Long id) {}

  private static final class PetTable extends QueryTable<Pet> {

    private final NonNullSelectable<Long> id = nonNullQueryColumn(ID);
    private final Selectable<String> name = nullableQueryColumn(NAME);

    private PetTable() {
      super(PET);
    }

    private PetTable(String alias) {
      super(PET, alias);
    }

    private NonNullSelectable<Long> id() {
      return id;
    }

    private Selectable<String> name() {
      return name;
    }

    @Override
    public PetTable as(String alias) {
      return new PetTable(alias);
    }
  }

  private static final class OtherPetTable extends QueryTable<OtherPet> {

    private final NonNullSelectable<Long> id = nonNullQueryColumn(OTHER_ID);

    private OtherPetTable() {
      super(OTHER_PET);
    }

    private OtherPetTable(String alias) {
      super(OTHER_PET, alias);
    }

    private NonNullSelectable<Long> id() {
      return id;
    }

    @Override
    public OtherPetTable as(String alias) {
      return new OtherPetTable(alias);
    }
  }

  private enum TestDialect implements Dialect {
    INSTANCE;

    private final DialectCapabilities capabilities =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    private final SqlRenderer renderer = new StandardSqlRenderer(this);

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

  private enum ReorderedParameterDialect implements Dialect {
    INSTANCE;

    private final DialectCapabilities capabilities =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    private final SqlRenderer renderer = new StandardSqlRenderer(this);

    @Override
    public DialectLowering lowering() {
      return input -> {
        if (input.statement() instanceof SelectStatement select
            && select.where().orElse(null) instanceof LogicalPredicate logical
            && logical.operator() == LogicalOperator.AND
            && logical.operands().size() == 2) {
          return new SelectStatement(
              select.distinct(),
              select.selections(),
              select.hiddenSelections(),
              select.fromClause(),
              new LogicalPredicate(
                  LogicalOperator.AND,
                  List.of(logical.operands().get(1), logical.operands().get(0))),
              select.groupBy(),
              select.having().orElse(null),
              select.orderBy(),
              select.pagination().orElse(null));
        }
        return input.statement();
      };
    }

    @Override
    public String id() {
      return "reordered-test";
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
