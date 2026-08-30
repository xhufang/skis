package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.core.ExecutionContext;
import io.skis.core.ExecutionOptions;
import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.IdentifierRules;
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
import io.skis.mapping.PropertyRuntime;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.Identifier;
import java.sql.Connection;
import java.sql.SQLException;
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
    assertEquals("Mimi", plans.argument(mimi));
    assertEquals("Fifi", plans.argument(fifi));
    assertSame(NoParameters.INSTANCE, plans.argument(null));
    assertEquals(0, plans.selectPlan(TABLE, null).parameterCount());
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

    assertThrows(QueryValidationException.class, () -> TABLE.name().eq(null));
    assertThrows(
        QueryValidationException.class, () -> plans().selectPlan(TABLE, alias.name().eq("Mimi")));
  }

  @Test
  void rejectsNullFastPathArgumentBeforeAcquiringAConnection() {
    QueryOperations operations =
        QueryRuntime.compile(
                EntityRuntimeRegistry.of(List.of(model())), TestDialect.INSTANCE)
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
        QueryRuntime.compile(
                EntityRuntimeRegistry.of(List.of(model())), TestDialect.INSTANCE)
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

    assertThrows(
        QueryExecutionException.class,
        () -> operations.findById(PET, 1L, options));
    assertSame(options, observed.get());

    observed.set(null);
    EntitySelectQuery<Pet> baseQuery = operations.selectFrom(TABLE);
    EntitySelectQuery<Pet> configuredQuery = baseQuery.withOptions(options);
    assertThrows(QueryExecutionException.class, configuredQuery::fetchList);
    assertSame(options, observed.get());
    assertSame(baseQuery, baseQuery.withOptions(ExecutionOptions.NONE));
  }

  private static EntityPlanSet<Pet> plans() {
    return new EntityPlanSet<>(model(), new QueryPlanCompiler(TestDialect.INSTANCE));
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

    private final QueryColumn<Pet, Long> id = queryColumn(ID);
    private final QueryColumn<Pet, String> name = queryColumn(NAME);

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
