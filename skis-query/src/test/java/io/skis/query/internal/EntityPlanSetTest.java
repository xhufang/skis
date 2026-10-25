package io.skis.query.internal;

import static io.skis.query.internal.QueryTestSupport.argument;
import static io.skis.query.internal.QueryTestSupport.arguments;
import static io.skis.query.internal.QueryTestSupport.selectPlan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.ExecutionOptions;
import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnostics.QuerySemanticReason;
import io.skis.core.SkisErrorCode;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.StatementPurpose;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.DialectLowering;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.jdbc.QueryExecutionException;
import io.skis.jdbc.internal.CompiledQueryPlan;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.JdbcReadContext;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.ParameterBindingObserver;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.NonNullSelectable;
import io.skis.query.QueryCondition;
import io.skis.query.QueryOperations;
import io.skis.query.QueryParameter;
import io.skis.query.QueryTable;
import io.skis.query.SelectQuery;
import io.skis.query.Selectable;
import io.skis.query.Sql;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.LogicalOperator;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
    assertSame(TABLE.id(), QueryTableModel.of(TABLE).internalColumn(ID));
    assertSame(TABLE.name(), QueryTableModel.of(TABLE).internalColumn(NAME));
    assertSame(
        SelectableSupport.expression(TABLE.id()), QueryTableModel.of(TABLE).selections().get(0));
    assertSame(
        SelectableSupport.expression(TABLE.name()), QueryTableModel.of(TABLE).selections().get(1));
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
  void selectionAndCountKeepTheOriginalGroupingFailureAndItsDiagnostics() {
    EntityRuntimeModel<Pet> runtimeModel = model();
    QueryPlanCompiler compiler =
        QueryTestSupport.compiler(
            EntityRuntimeRegistry.of(List.of(runtimeModel)), TestDialect.INSTANCE);
    CompiledQueryStructure base = QueryTestSupport.compile(TABLE, List.of(), null);
    CompiledQueryStructure grouped =
        new CompiledQueryStructure(
            base.fromClause(),
            base.selections(),
            base.where(),
            List.of(SelectableSupport.expression(TABLE.id())),
            null,
            List.of(),
            base.parameterSources(),
            base.parameterReferences(),
            base.parameterSlots(),
            base.builtinParameterSources(),
            base.parameters(),
            null);
    QueryValidationException direct =
        assertThrows(
            QueryValidationException.class,
            () ->
                SemanticValidator.validateComplete(
                    new SelectStatement(
                        false,
                        grouped.selections(),
                        List.of(),
                        grouped.fromClause(),
                        null,
                        grouped.groupBy(),
                        null,
                        List.of(),
                        null)));
    QueryValidationException selection =
        assertThrows(
            QueryValidationException.class,
            () ->
                compiler.compileQuery(
                    runtimeModel, TABLE, grouped, null, StatementPurpose.PRIMARY));
    QueryValidationException count =
        assertThrows(
            QueryValidationException.class,
            () ->
                compiler.compileCount(
                    SelectedResult.entity(TABLE),
                    grouped,
                    false,
                    arguments(grouped),
                    null,
                    StatementPurpose.PAGE_COUNT,
                    null));

    assertEquals(SkisErrorCode.QUERY_GROUPING_CONTRACT, selection.code());
    assertEquals(selection.code(), count.code());
    assertEquals(direct.diagnostics(), selection.diagnostics());
    assertEquals(direct.diagnostics(), count.diagnostics());
    assertEquals(1, count.diagnostics().querySemantics().missingPropertyOrdinal());
    assertNull(selection.getCause());
    assertNull(count.getCause());
  }

  @Test
  void countCannotHideAnIllegalAggregateInAPrunedOriginalPredicate() {
    EntityRuntimeModel<Pet> runtimeModel = model();
    QueryPlanCompiler compiler =
        QueryTestSupport.compiler(
            EntityRuntimeRegistry.of(List.of(runtimeModel)), TestDialect.INSTANCE);
    CompiledQueryStructure base = QueryTestSupport.compile(TABLE, List.of(), null);
    CompiledQueryStructure original =
        new CompiledQueryStructure(
            base.fromClause(),
            base.selections(),
            new LogicalPredicate(
                LogicalOperator.AND,
                List.of(
                    new BooleanPredicate(false),
                    new InPredicate<>(AggregateExpression.countAll(), List.of(), false))),
            List.of(),
            null,
            List.of(),
            base.parameterSources(),
            base.parameterReferences(),
            base.parameterSlots(),
            base.builtinParameterSources(),
            base.parameters(),
            null);
    CompiledQueryStructure pruned =
        new CompiledQueryStructure(
            base.fromClause(),
            base.selections(),
            new BooleanPredicate(false),
            List.of(),
            null,
            List.of(),
            base.parameterSources(),
            base.parameterReferences(),
            base.parameterSlots(),
            base.builtinParameterSources(),
            base.parameters(),
            original);

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () ->
                compiler.compileCount(
                    SelectedResult.entity(TABLE),
                    pruned,
                    false,
                    arguments(pruned),
                    null,
                    StatementPurpose.PAGE_COUNT,
                    null));

    assertEquals(SkisErrorCode.QUERY_AGGREGATE_CONTRACT, failure.code());
    assertEquals(
        QuerySemanticReason.AGGREGATE_IN_CLAUSE, failure.diagnostics().querySemantics().reason());
    assertEquals("WHERE", failure.diagnostics().query().clause());
    assertNull(failure.getCause());
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
    List<List<Object>> observed = new ArrayList<>();
    bindings.clear();
    assertEquals(6, plan.parameterBinder().bindObserved(statement, 4, argument(TABLE, repeated),
        JdbcWriteContext.EMPTY, bindingObserver(observed)));
    assertEquals(List.of(List.of(4, "Mimi"), List.of(5, "Mimi")), bindings);
    assertEquals(List.of(List.of(4, String.class, "Mimi"), List.of(5, String.class, "Mimi")), observed);

    for (int limit : List.of(0, 1)) {
      bindings.clear();
      observed.clear();
      var demands = new java.util.concurrent.atomic.AtomicInteger();
      ParameterBindingObserver limited = new ParameterBindingObserver() {
        @Override public boolean needsMore() {
          demands.incrementAndGet();
          return observed.size() < limit;
        }
        @Override public void bound(int index, Class<?> type,
            @org.jspecify.annotations.Nullable Object value) {
          assertTrue(observed.size() < limit, "observer received a value after stopping");
          observed.add(java.util.Arrays.asList(index, type, value));
        }
        @Override public void unavailable() { throw new AssertionError("framework binder is opaque"); }
      };
      assertEquals(6, plan.parameterBinder().bindObserved(statement, 4, argument(TABLE, repeated),
          JdbcWriteContext.EMPTY, limited));
      assertEquals(List.of(List.of(4, "Mimi"), List.of(5, "Mimi")), bindings);
      assertEquals(limit, observed.size());
      assertEquals(limit + 1, demands.get(), "binder kept polling a stopped observer");
    }
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
  void sharedParameterViewsUseTheMappedBuiltinBinderInBothEncounterOrders() throws Exception {
    EntityRuntimeModel<Pet> runtimeModel = model();
    QueryPlanCompiler compiler = QueryTestSupport.compiler(
        EntityRuntimeRegistry.of(List.of(runtimeModel)), TestDialect.INSTANCE);
    QueryParameter<Long> parameter = Sql.parameter(Long.class, "id");
    QueryCondition view = Sql.value(parameter).isNotNull();
    QueryCondition column = TABLE.id().eq(parameter);
    for (QueryCondition condition : List.of(view.and(column), column.and(view))) {
      CompiledQueryStructure structure = QueryModels.description(
          Sql.select(TABLE).from(TABLE).where(condition)).state().structure();
      CompiledQueryPlan<Pet, Object> plan = compiler.compileQuery(
          runtimeModel, TABLE, structure, null, StatementPurpose.PRIMARY);
      assertEquals(List.of(0, 0),
          plan.renderedSql().parameters().stream().map(ParameterSlot::ordinal).toList());
      List<List<Object>> bindings = new ArrayList<>();
      PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
          EntityPlanSetTest.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
          (ignored, method, arguments) -> {
            if (method.getName().equals("setLong")) {
              bindings.add(List.of(arguments[0], arguments[1]));
            }
            return null;
          });
      assertEquals(3, plan.parameterBinder().bind(statement, 1,
          structure.arguments(QueryParameters.of(parameter, 7L)), JdbcWriteContext.EMPTY));
      assertEquals(List.of(List.of(1, 7L), List.of(2, 7L)), bindings);
    }
  }

  @Test
  void sharedParameterCodecChecksCoverTheExactBuiltinRepresentations() {
    for (BuiltinParameterCase<?> fixture : List.of(
        new BuiltinParameterCase<>(Boolean.class, JdbcCodecs.BOOLEAN),
        new BuiltinParameterCase<>(Byte.class, JdbcCodecs.BYTE),
        new BuiltinParameterCase<>(Short.class, JdbcCodecs.SHORT),
        new BuiltinParameterCase<>(Integer.class, JdbcCodecs.INTEGER),
        new BuiltinParameterCase<>(Long.class, JdbcCodecs.LONG),
        new BuiltinParameterCase<>(Float.class, JdbcCodecs.FLOAT),
        new BuiltinParameterCase<>(Double.class, JdbcCodecs.DOUBLE),
        new BuiltinParameterCase<>(BigInteger.class, JdbcCodecs.BIG_INTEGER),
        new BuiltinParameterCase<>(BigDecimal.class, JdbcCodecs.BIG_DECIMAL),
        new BuiltinParameterCase<>(Character.class, JdbcCodecs.CHARACTER),
        new BuiltinParameterCase<>(String.class, JdbcCodecs.STRING),
        new BuiltinParameterCase<>(byte[].class, JdbcCodecs.BYTES),
        new BuiltinParameterCase<>(UUID.class, JdbcCodecs.UUID),
        new BuiltinParameterCase<>(LocalDate.class, JdbcCodecs.LOCAL_DATE),
        new BuiltinParameterCase<>(java.sql.Date.class, JdbcCodecs.SQL_DATE),
        new BuiltinParameterCase<>(LocalTime.class, JdbcCodecs.LOCAL_TIME),
        new BuiltinParameterCase<>(java.sql.Time.class, JdbcCodecs.SQL_TIME),
        new BuiltinParameterCase<>(OffsetTime.class, JdbcCodecs.OFFSET_TIME),
        new BuiltinParameterCase<>(LocalDateTime.class, JdbcCodecs.LOCAL_DATE_TIME),
        new BuiltinParameterCase<>(java.sql.Timestamp.class, JdbcCodecs.SQL_TIMESTAMP),
        new BuiltinParameterCase<>(Instant.class, JdbcCodecs.INSTANT),
        new BuiltinParameterCase<>(OffsetDateTime.class, JdbcCodecs.OFFSET_DATE_TIME))) {
      assertBuiltinParameterSource(fixture);
    }
  }

  private static <V> void assertBuiltinParameterSource(BuiltinParameterCase<V> fixture) {
    PropertyMeta<BuiltinValue, V> property = new PropertyMeta<>(
        0, "value", fixture.javaType(), ColumnMeta.of("value", false));
    EntityMeta<BuiltinValue> entity = EntityMeta.simple(BuiltinValue.class,
        new TableMeta("", "", "builtin_value"), List.of(property),
        new PrimaryKeyMeta<>(List.of(property)), false);
    class ValueTable extends QueryTable<BuiltinValue> {
      ValueTable(String alias) { super(entity, alias); }
      @Override public ValueTable as(String alias) { return new ValueTable(alias); }
      Selectable<V> value() { return queryColumn(property); }
    }
    EntityRuntimeModel<BuiltinValue> model = new EntityRuntimeModel<>(entity,
        layout -> (rows, context) -> new BuiltinValue("unused"),
        List.of(new PropertyRuntime<>(property, fixture.codec())));
    EntityRuntimeRegistry registry = EntityRuntimeRegistry.of(List.of(model));
    ValueTable table = new ValueTable("b");
    TableRuntimeScope scope = TableRuntimeScope.resolve(
        registry, FromClause.of(QueryTableModel.of(table).tableExpression()));
    Selectable<V> value = table.value();
    ResolvedValueMapping.requireBuiltinParameterSource(value, scope.outputCodec(value));
  }

  private record BuiltinValue(Object value) {}

  private record BuiltinParameterCase<V>(Class<V> javaType, JdbcTypeCodec<V> codec) {}

  @Test
  void sharedParameterViewsRejectDelegatingCodecsBeforeContentCountOrPruning() {
    JdbcTypeCodec<Long> delegated = new JdbcTypeCodec<>() {
      @Override
      public @Nullable Long read(ResultSet rows, int index, JdbcReadContext context)
          throws SQLException {
        return JdbcCodecs.LONG.read(rows, index, context);
      }

      @Override
      public void bind(PreparedStatement statement, int index, @Nullable Long value,
          JdbcWriteContext context) throws SQLException {
        JdbcCodecs.LONG.bind(statement, index, value, context);
      }
    };
    EntityRuntimeModel<Pet> runtimeModel = new EntityRuntimeModel<>(PET,
        layout -> (rows, context) -> new Pet(1L, "Mimi"),
        List.of(new PropertyRuntime<>(ID, delegated), new PropertyRuntime<>(NAME, JdbcCodecs.STRING)));
    QueryPlanCompiler compiler = QueryTestSupport.compiler(
        EntityRuntimeRegistry.of(List.of(runtimeModel)), TestDialect.INSTANCE);
    QueryParameter<Long> parameter = Sql.parameter(Long.class, "id");
    Selectable<Long> view = Sql.value(parameter);
    QueryCondition column = TABLE.id().eq(parameter);
    for (QueryCondition condition : List.of(
        view.isNotNull().and(column), column.and(view.isNotNull()),
        view.inParameters(List.of()).and(column), column.and(view.inParameters(List.of())))) {
      SelectQueryState<Pet> state = QueryModels.description(
          Sql.select(TABLE).from(TABLE).where(condition)).state();
      CompiledQueryStructure content = state.structure();
      CompiledQueryStructure count = state.countStructure();
      QueryParameters parameters = QueryParameters.of(parameter, 7L);
      assertEquals(SkisErrorCode.QUERY_PARAMETER_LAYOUT,
          assertThrows(QueryValidationException.class, () -> compiler.compileQuery(
              runtimeModel, TABLE, content, null, StatementPurpose.PRIMARY)).code());
      assertEquals(SkisErrorCode.QUERY_PARAMETER_LAYOUT,
          assertThrows(QueryValidationException.class, () -> compiler.compileCount(
              SelectedResult.entity(TABLE), count, false, count.arguments(parameters),
              null, StatementPurpose.PAGE_COUNT, null)).code());
    }
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
            FromClause.of(QueryTableModel.of(TABLE).tableExpression()),
            List.of(),
            new ComparisonPredicate<>(
                SelectableSupport.expression(TABLE.id()), ComparisonOperator.EQUAL, slot),
            List.of(),
            null,
            List.of(),
            layout.parameterSources(),
            layout.parameterReferences(),
            layout.parameterSlots(),
            layout.builtinParameterSources(),
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
    List<List<Object>> observed = new ArrayList<>();
    bindings.clear();
    assertEquals(3, plan.parameterBinder().bindObserved(statement, 1, argument(TABLE, predicate),
        JdbcWriteContext.EMPTY, bindingObserver(observed)));
    assertEquals(List.of(List.of("setLong", 1, 1L), List.of("setString", 2, "Mi%")), bindings);
    assertEquals(List.of(List.of(1, Long.class, 1L), List.of(2, String.class, "Mi%")), observed);
  }

  private static ParameterBindingObserver bindingObserver(List<List<Object>> observed) {
    return new ParameterBindingObserver() {
      @Override public void bound(int index, Class<?> type, @org.jspecify.annotations.Nullable Object value) {
        observed.add(java.util.Arrays.asList(index, type, value));
      }
      @Override public void unavailable() { throw new AssertionError("framework binder must be observable"); }
    };
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
  void publishesOnePlanIdentityUnderConcurrentFirstUse() throws Exception {
    CountDownLatch bothCompiling = new CountDownLatch(2);
    AtomicBoolean coordinateRendering = new AtomicBoolean();
    Dialect dialect =
        new Dialect() {
          private final SqlRenderer delegate = new StandardSqlRenderer(this);
          private final SqlRenderer renderer =
              input -> {
                if (coordinateRendering.get()) {
                  bothCompiling.countDown();
                  try {
                    assertTrue(bothCompiling.await(5, TimeUnit.SECONDS));
                  } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                        "interrupted while coordinating plan publication", failure);
                  }
                }
                return delegate.render(input);
              };

          @Override
          public String id() {
            return TestDialect.INSTANCE.id();
          }

          @Override
          public IdentifierRules identifierRules() {
            return TestDialect.INSTANCE.identifierRules();
          }

          @Override
          public DialectCapabilities capabilities() {
            return TestDialect.INSTANCE.capabilities();
          }

          @Override
          public SqlRenderer renderer() {
            return renderer;
          }
        };
    EntityRuntimeModel<Pet> runtimeModel = model();
    EntityPlanSet<Pet> plans =
        new EntityPlanSet<>(
            runtimeModel,
            QueryTestSupport.compiler(EntityRuntimeRegistry.of(List.of(runtimeModel)), dialect));
    // Leave findById prewarming unblocked, then hold both first-use compilers before publication.
    coordinateRendering.set(true);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var first = workers.submit(() -> selectPlan(plans, TABLE, TABLE.name().eq("Mimi")));
      var second = workers.submit(() -> selectPlan(plans, TABLE, TABLE.name().eq("Fifi")));

      CompiledQueryPlan<Pet, Object> published = first.get(10, TimeUnit.SECONDS);
      assertSame(published, second.get(10, TimeUnit.SECONDS));
      assertEquals(0L, bothCompiling.getCount());
      assertSame(published, selectPlan(plans, TABLE, TABLE.name().eq("Poppy")));
    } finally {
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
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
        DialectCapabilities.of(
            DialectFeature.SCHEMA_QUALIFIED_TABLES, DialectFeature.COUNT_AGGREGATE);
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
