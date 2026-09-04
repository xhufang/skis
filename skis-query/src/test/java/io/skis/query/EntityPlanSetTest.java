package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.core.ExecutionContext;
import io.skis.core.ExecutionOptions;
import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.RenderedSql;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardIdentifierRules;
import io.skis.dialect.StandardSqlRenderer;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.jdbc.ConnectionProvider;
import io.skis.jdbc.JdbcExecutor;
import io.skis.jdbc.QueryExecutionException;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mapping.JdbcCodecs;
import io.skis.mapping.JdbcWriteContext;
import io.skis.mapping.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.Identifier;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
  private static final PetTable TABLE = new PetTable();

  @Test
  void prewarmsFindByIdAndReusesOneBoundedPlanPerProperty() {
    EntityPlanSet<Pet> plans = plans();

    CompiledQueryPlan<Pet, Object> findById = plans.findByIdPlan();
    CompiledQueryPlan<Pet, Object> firstName = plans.selectPlan(TABLE, TABLE.name().eq("Mimi"));
    CompiledQueryPlan<Pet, Object> secondName = plans.selectPlan(TABLE, TABLE.name().eq("Fifi"));

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
    QueryPredicate<Pet> mimi = TABLE.name().eq("Mimi");
    QueryPredicate<Pet> fifi = TABLE.name().eq("Fifi");

    assertSame(plans.selectPlan(TABLE, mimi), plans.selectPlan(TABLE, fifi));
    assertEquals(List.of("Mimi"), ((QueryArguments) plans.argument(mimi)).values());
    assertEquals(List.of("Fifi"), ((QueryArguments) plans.argument(fifi)).values());
    QueryPredicate<Pet> firstComplex = TABLE.name().like("Mi%").and(TABLE.id().between(1L, 9L));
    QueryPredicate<Pet> secondComplex = TABLE.name().like("Mo%").and(TABLE.id().between(2L, 10L));
    assertEquals(firstComplex.compile().ast(), secondComplex.compile().ast());
    assertEquals(List.of("Mi%", 1L, 9L), firstComplex.compile().arguments());
    assertEquals(List.of("Mo%", 2L, 10L), secondComplex.compile().arguments());
    assertSame(NoParameters.INSTANCE, plans.argument(null));
    assertEquals(0, plans.selectPlan(TABLE, null).parameterCount());
  }

  @Test
  void compilesGroupedPredicatesWithStableParameterEncounterOrder() {
    EntityPlanSet<Pet> plans = plans();
    QueryPredicate<Pet> predicate =
        TABLE.name().isNull().or(TABLE.name().like("Mi%")).and(TABLE.id().ge(1L));

    CompiledQueryPlan<Pet, Object> plan = plans.selectPlan(TABLE, predicate);

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" "
            + "WHERE (\"pet\".\"pet_name\" IS NULL OR \"pet\".\"pet_name\" LIKE ?) "
            + "AND \"pet\".\"id\" >= ?",
        plan.sql());
    assertEquals(List.of("Mi%", 1L), ((QueryArguments) plans.argument(predicate)).values());
  }

  @Test
  void bindsComplexPredicateArgumentsInPlaceholderEncounterOrder() throws Exception {
    EntityPlanSet<Pet> plans = plans();
    QueryPredicate<Pet> predicate = TABLE.name().like("Mi%").and(TABLE.id().ge(1L));
    CompiledQueryPlan<Pet, Object> plan = plans.selectPlan(TABLE, predicate);
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
            .bind(statement, 3, plans.argument(predicate), JdbcWriteContext.EMPTY);

    assertEquals(5, nextIndex);
    assertEquals(List.of(List.of("setString", 3, "Mi%"), List.of("setLong", 4, 1L)), bindings);
  }

  @Test
  void followsRendererParameterOrderWhenDialectReordersPlaceholders() throws Exception {
    EntityPlanSet<Pet> plans =
        new EntityPlanSet<>(
            model(),
            new QueryPlanCompiler(
                EntityRuntimeRegistry.empty(), ReorderedParameterDialect.INSTANCE),
            projectionPlans());
    QueryPredicate<Pet> predicate = TABLE.name().like("Mi%").and(TABLE.id().ge(1L));
    CompiledQueryPlan<Pet, Object> plan = plans.selectPlan(TABLE, predicate);
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
            .bind(statement, 1, plans.argument(predicate), JdbcWriteContext.EMPTY);

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

    assertEquals(prefix + "\"pet\".\"id\" <> ?", plans.selectPlan(TABLE, TABLE.id().ne(1L)).sql());
    assertEquals(prefix + "\"pet\".\"id\" > ?", plans.selectPlan(TABLE, TABLE.id().gt(1L)).sql());
    assertEquals(prefix + "\"pet\".\"id\" >= ?", plans.selectPlan(TABLE, TABLE.id().ge(1L)).sql());
    assertEquals(prefix + "\"pet\".\"id\" < ?", plans.selectPlan(TABLE, TABLE.id().lt(1L)).sql());
    assertEquals(prefix + "\"pet\".\"id\" <= ?", plans.selectPlan(TABLE, TABLE.id().le(1L)).sql());
    assertEquals(
        prefix + "\"pet\".\"pet_name\" IS NOT NULL",
        plans.selectPlan(TABLE, TABLE.name().isNotNull()).sql());
    assertEquals(
        prefix + "\"pet\".\"id\" BETWEEN ? AND ?",
        plans.selectPlan(TABLE, TABLE.id().between(1L, 9L)).sql());
    assertEquals(
        prefix + "\"pet\".\"pet_name\" LIKE ?",
        plans.selectPlan(TABLE, TABLE.name().like("Mi%")).sql());
    assertEquals(
        prefix + "\"pet\".\"id\" IN (?, ?)",
        plans.selectPlan(TABLE, TABLE.id().in(List.of(1L, 2L))).sql());
    assertEquals(
        prefix + "\"pet\".\"id\" NOT IN (?, ?)",
        plans.selectPlan(TABLE, TABLE.id().notIn(List.of(1L, 2L))).sql());
    assertEquals(
        prefix + "NOT (\"pet\".\"id\" = ?)",
        plans.selectPlan(TABLE, TABLE.id().eq(1L).not()).sql());
  }

  @Test
  void compilesEmptyMembershipWithoutParameters() {
    EntityPlanSet<Pet> plans = plans();
    QueryPredicate<Pet> emptyIn = TABLE.id().in(List.of());
    QueryPredicate<Pet> emptyNotIn = TABLE.id().notIn(List.of());

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" WHERE 1 = 0",
        plans.selectPlan(TABLE, emptyIn).sql());
    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" WHERE 1 = 1",
        plans.selectPlan(TABLE, emptyNotIn).sql());
    assertSame(NoParameters.INSTANCE, plans.argument(emptyIn));
    assertSame(NoParameters.INSTANCE, plans.argument(emptyNotIn));
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
    SelectQuery<Pet, Pet> base = operations.selectFrom(TABLE);
    SelectQuery<Pet, Pet> filtered =
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
            .mapToObj(index -> plans.selectPlan(TABLE, TABLE.name().eq("pet-" + index)))
            .toList();

    CompiledQueryPlan<Pet, Object> published = observed.getFirst();
    observed.forEach(plan -> assertSame(published, plan));
  }

  @Test
  void rejectsAmbiguousNullAndPredicatesFromAnotherTable() {
    PetTable alias = TABLE.as("p");

    assertThrows(QueryValidationException.class, () -> TABLE.name().eq((String) null));
    assertThrows(
        QueryValidationException.class, () -> plans().selectPlan(TABLE, alias.name().eq("Mimi")));
    assertThrows(
        QueryValidationException.class,
        () -> plans().selectPlan(TABLE, TABLE.id().eq(1L).and(alias.name().isNotNull())));
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

    assertThrows(QueryValidationException.class, () -> operations.findById(PET, null));
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

    assertThrows(QueryExecutionException.class, () -> operations.findById(PET, 1L, options));
    assertSame(options, observed.get());

    observed.set(null);
    SelectQuery<Pet, Pet> baseQuery = operations.selectFrom(TABLE);
    SelectQuery<Pet, Pet> configuredQuery = baseQuery.withOptions(options);
    assertThrows(QueryExecutionException.class, configuredQuery::fetchList);
    assertSame(options, observed.get());
    assertSame(baseQuery, baseQuery.withOptions(ExecutionOptions.NONE));
  }

  private static EntityPlanSet<Pet> plans() {
    return new EntityPlanSet<>(
        model(),
        new QueryPlanCompiler(EntityRuntimeRegistry.empty(), TestDialect.INSTANCE),
        projectionPlans());
  }

  private static ProjectionPlanCache projectionPlans() {
    return new ProjectionPlanCache(
        ProjectionPlanCache.DEFAULT_MAXIMUM_SIZE,
        ProjectionPlanCache.DEFAULT_EXPIRE_AFTER_ACCESS,
        System::nanoTime);
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

  private enum ReorderedParameterDialect implements Dialect {
    INSTANCE;

    private static final String ORIGINAL_SQL =
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" "
            + "WHERE \"pet\".\"pet_name\" LIKE ? AND \"pet\".\"id\" >= ?";
    private static final String REORDERED_SQL =
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" "
            + "WHERE \"pet\".\"id\" >= ? AND \"pet\".\"pet_name\" LIKE ?";

    private final DialectCapabilities capabilities =
        DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES);
    private final SqlRenderer standardRenderer =
        new StandardSqlRenderer(id(), identifierRules(), capabilities);
    private final SqlRenderer renderer =
        statement -> {
          RenderedSql rendered = standardRenderer.render(statement);
          if (!rendered.sql().equals(ORIGINAL_SQL)) {
            return rendered;
          }
          return new RenderedSql(
              REORDERED_SQL, List.of(rendered.parameters().get(1), rendered.parameters().get(0)));
        };

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
