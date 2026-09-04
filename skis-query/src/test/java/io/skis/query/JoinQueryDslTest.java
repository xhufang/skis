package io.skis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.ExecutionContext;
import io.skis.dialect.Dialect;
import io.skis.dialect.DialectCapabilities;
import io.skis.dialect.DialectFeature;
import io.skis.dialect.IdentifierRules;
import io.skis.dialect.SqlRenderer;
import io.skis.dialect.StandardIdentifierRules;
import io.skis.dialect.StandardSqlRenderer;
import io.skis.jdbc.ConnectionProvider;
import io.skis.jdbc.JdbcExecutor;
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
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SqlPredicate;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JoinQueryDslTest {

  private static final PropertyMeta<Pet, Long> PET_ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, Long> PET_OWNER_ID =
      new PropertyMeta<>(1, "ownerId", Long.class, ColumnMeta.of("owner_id", false));
  private static final PropertyMeta<Pet, String> PET_NAME =
      new PropertyMeta<>(2, "name", String.class, ColumnMeta.of("pet_name", false));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "shelter", "pet"),
          List.of(PET_ID, PET_OWNER_ID, PET_NAME),
          new PrimaryKeyMeta<>(List.of(PET_ID)),
          false);

  private static final PropertyMeta<Owner, Long> OWNER_ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Owner, String> OWNER_NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("owner_name", true));
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
  void selectsAJoinedEntityFromAnIndependentRootAndKeepsTheOriginalQueryImmutable() {
    QueryOperations operations = operations();
    SelectQuery<Pet, Owner> base = operations.select(OWNER_TABLE).from(PET_TABLE);
    SelectQuery<Pet, Owner> joined =
        base.join(OWNER_TABLE).on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()));

    assertNotSame(base, joined);
    assertThrows(
        QueryValidationException.class,
        () -> ((DefaultSelectQuery<Pet, Owner>) base).compilation(QueryPagination.None.INSTANCE));
    assertThrows(
        QueryValidationException.class,
        () -> ((DefaultSelectQuery<Pet, Owner>) base).countCompilation());

    QueryCompilation<Owner> compilation =
        ((DefaultSelectQuery<Pet, Owner>) joined).compilation(QueryPagination.None.INSTANCE);
    assertEquals(
        "SELECT \"owner\".\"id\", \"owner\".\"owner_name\" "
            + "FROM \"shelter\".\"pet\" INNER JOIN \"shelter\".\"owner\" "
            + "ON \"pet\".\"owner_id\" = \"owner\".\"id\"",
        compilation.plan().sql());
  }

  @Test
  void selectsAJoinedScalarFromAnIndependentRoot() {
    SelectQuery<Pet, Long> query =
        operations()
            .select(OWNER_TABLE.id())
            .from(PET_TABLE)
            .join(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()));

    QueryCompilation<Long> compilation =
        ((DefaultSelectQuery<Pet, Long>) query).compilation(QueryPagination.None.INSTANCE);

    assertEquals(
        "SELECT \"owner\".\"id\" FROM \"shelter\".\"pet\" "
            + "INNER JOIN \"shelter\".\"owner\" ON \"pet\".\"owner_id\" = \"owner\".\"id\"",
        compilation.plan().sql());
  }

  @Test
  void compilesOnThenWhereParametersWithOneDenseStatementOrder() throws Exception {
    QueryCondition on =
        PET_TABLE
            .ownerId()
            .eq(OWNER_TABLE.id())
            .and(OWNER_TABLE.name().eq("Ada"));
    SelectQuery<Pet, Pet> query =
        operations()
            .selectFrom(PET_TABLE)
            .join(OWNER_TABLE)
            .on(on)
            .where(PET_TABLE.id().gt(10L));

    QueryCompilation<Pet> compilation =
        ((DefaultSelectQuery<Pet, Pet>) query).compilation(QueryPagination.None.INSTANCE);

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"owner_id\", \"pet\".\"pet_name\" "
            + "FROM \"shelter\".\"pet\" INNER JOIN \"shelter\".\"owner\" "
            + "ON \"pet\".\"owner_id\" = \"owner\".\"id\" AND \"owner\".\"owner_name\" = ? "
            + "WHERE \"pet\".\"id\" > ?",
        compilation.plan().sql());
    assertEquals(List.of("Ada", 10L), ((QueryArguments) compilation.argument()).values());
    assertEquals(
        List.of(0, 1),
        compilation.plan().renderedSql().parameters().stream()
            .map(slot -> slot.ordinal())
            .toList());

    List<List<Object>> bindings = new ArrayList<>();
    PreparedStatement statement =
        (PreparedStatement)
            Proxy.newProxyInstance(
                JoinQueryDslTest.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (ignored, method, arguments) -> {
                  if (method.getName().equals("setString") || method.getName().equals("setLong")) {
                    bindings.add(List.of(method.getName(), arguments[0], arguments[1]));
                  }
                  return null;
                });
    int nextIndex =
        compilation
            .plan()
            .parameterBinder()
            .bind(statement, 3, compilation.argument(), JdbcWriteContext.EMPTY);

    assertEquals(5, nextIndex);
    assertEquals(
        List.of(List.of("setString", 3, "Ada"), List.of("setLong", 4, 10L)), bindings);
  }

  @Test
  void exposesEveryJoinKindAndKeepsJoinAsTheInnerAlias() {
    assertJoinSql(
        operations()
            .selectFrom(PET_TABLE)
            .join(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id())),
        " INNER JOIN ");
    assertJoinSql(
        operations()
            .selectFrom(PET_TABLE)
            .innerJoin(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id())),
        " INNER JOIN ");
    assertJoinSql(
        operations()
            .selectFrom(PET_TABLE)
            .leftJoin(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id())),
        " LEFT JOIN ");
    assertJoinSql(
        operations()
            .selectFrom(PET_TABLE)
            .rightJoin(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id())),
        " RIGHT JOIN ");
    assertJoinSql(
        operations()
            .selectFrom(PET_TABLE)
            .fullJoin(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id())),
        " FULL JOIN ");
    assertJoinSql(
        operations().selectFrom(PET_TABLE).crossJoin(OWNER_TABLE), " CROSS JOIN ");
  }

  @Test
  void validatesOnAndWhereAgainstTheirActualTableReferenceScope() {
    OwnerTable reviewer = OWNER_TABLE.as("reviewer");
    SelectQuery<Pet, Pet> futureReference =
        operations()
            .selectFrom(PET_TABLE)
            .join(OWNER_TABLE)
            .on(OWNER_TABLE.id().eq(reviewer.id()))
            .join(reviewer)
            .on(OWNER_TABLE.id().eq(reviewer.id()));
    SelectQuery<Pet, Pet> invisibleWhere =
        operations().selectFrom(PET_TABLE).where(OWNER_TABLE.id().isNotNull());

    assertThrows(
        QueryValidationException.class,
        () ->
            ((DefaultSelectQuery<Pet, Pet>) futureReference)
                .compilation(QueryPagination.None.INSTANCE));
    assertThrows(
        QueryValidationException.class,
        () ->
            ((DefaultSelectQuery<Pet, Pet>) invisibleWhere)
                .compilation(QueryPagination.None.INSTANCE));
  }

  @Test
  void columnComparisonsReuseCentralTypeRulesAndCreateNoParameters() {
    List<QueryCondition> conditions =
        List.of(
            PET_TABLE.ownerId().eq(OWNER_TABLE.id()),
            PET_TABLE.ownerId().ne(OWNER_TABLE.id()),
            PET_TABLE.ownerId().gt(OWNER_TABLE.id()),
            PET_TABLE.ownerId().ge(OWNER_TABLE.id()),
            PET_TABLE.ownerId().lt(OWNER_TABLE.id()),
            PET_TABLE.ownerId().le(OWNER_TABLE.id()));

    List<ComparisonOperator> operators =
        List.of(
            ComparisonOperator.EQUAL,
            ComparisonOperator.NOT_EQUAL,
            ComparisonOperator.GREATER_THAN,
            ComparisonOperator.GREATER_THAN_OR_EQUAL,
            ComparisonOperator.LESS_THAN,
            ComparisonOperator.LESS_THAN_OR_EQUAL);
    for (int index = 0; index < conditions.size(); index++) {
      ConditionCompilation compiled = compileCondition(conditions.get(index));
      ComparisonPredicate<?> comparison = (ComparisonPredicate<?>) compiled.ast();
      assertEquals(operators.get(index), comparison.operator());
      assertEquals(Nullability.NON_NULL, comparison.nullability());
      assertTrue(compiled.arguments().isEmpty());
      assertTrue(compiled.parameterColumns().isEmpty());
    }

    ComparisonPredicate<?> nullable =
        (ComparisonPredicate<?>)
            compileCondition(PET_TABLE.name().eq(OWNER_TABLE.name())).ast();
    assertEquals(Nullability.NULLABLE, nullable.nullability());
  }

  @Test
  void keepsWideConditionValuesOutsideItsStableAstStructure() {
    QueryCondition first =
        PET_TABLE.ownerId().eq(OWNER_TABLE.id()).and(OWNER_TABLE.name().eq("Ada"));
    QueryCondition second =
        PET_TABLE.ownerId().eq(OWNER_TABLE.id()).and(OWNER_TABLE.name().eq("Grace"));

    ConditionCompilation firstCompiled = compileCondition(first);
    ConditionCompilation secondCompiled = compileCondition(second);

    assertEquals(firstCompiled.ast(), secondCompiled.ast());
    assertEquals(List.of("Ada"), firstCompiled.arguments());
    assertEquals(List.of("Grace"), secondCompiled.arguments());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void rejectsRawColumnComparisonsThatBypassTheGenericJavaTypeCheck() {
    QueryColumn ownerId = PET_TABLE.ownerId();
    QueryColumn ownerName = OWNER_TABLE.name();

    assertThrows(QueryValidationException.class, () -> ownerId.eq(ownerName));
  }

  private static void assertJoinSql(SelectQuery<Pet, Pet> query, String keyword) {
    String sql =
        ((DefaultSelectQuery<Pet, Pet>) query)
            .compilation(QueryPagination.None.INSTANCE)
            .plan()
            .sql();
    assertTrue(sql.contains(keyword));
    assertEquals(keyword.contains("CROSS"), !sql.contains(" ON "));
  }

  private static ConditionCompilation compileCondition(QueryCondition condition) {
    QueryConditionCompiler compiler = new QueryConditionCompiler();
    SqlPredicate ast = QueryConditions.compile(condition, compiler);
    return new ConditionCompilation(
        ast, compiler.parameterColumns(), compiler.arguments());
  }

  private static QueryOperations operations() {
    EntityRuntimeRegistry registry =
        EntityRuntimeRegistry.of(List.of(petModel(), ownerModel()));
    return QueryRuntime.compile(registry, TestDialect.INSTANCE)
        .bind(
            new JdbcExecutor(
                new ConnectionProvider() {
                  @Override
                  public Connection acquire(ExecutionContext context) {
                    throw new AssertionError("query compilation must not acquire JDBC");
                  }

                  @Override
                  public void release(Connection connection, ExecutionContext context) {}
                }));
  }

  private static EntityRuntimeModel<Pet> petModel() {
    return new EntityRuntimeModel<>(
        PET,
        layout -> (resultSet, context) -> new Pet(1L, 2L, "Mimi"),
        List.of(
            new PropertyRuntime<>(PET_ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(PET_OWNER_ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(PET_NAME, JdbcCodecs.STRING)));
  }

  private static EntityRuntimeModel<Owner> ownerModel() {
    return new EntityRuntimeModel<>(
        OWNER,
        layout -> (resultSet, context) -> new Owner(2L, "Ada"),
        List.of(
            new PropertyRuntime<>(OWNER_ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(OWNER_NAME, JdbcCodecs.STRING)));
  }

  private record Pet(Long id, Long ownerId, String name) {}

  private record Owner(Long id, String name) {}

  private record ConditionCompilation(
      SqlPredicate ast, List<QueryColumn<?, ?>> parameterColumns, List<Object> arguments) {}

  private static final class PetTable extends QueryTable<Pet> {

    private final NonNullQueryColumn<Pet, Long> id = nonNullQueryColumn(PET_ID);
    private final NonNullQueryColumn<Pet, Long> ownerId = nonNullQueryColumn(PET_OWNER_ID);
    private final NonNullQueryColumn<Pet, String> name = nonNullQueryColumn(PET_NAME);

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

  private static final class OwnerTable extends QueryTable<Owner> {

    private final NonNullQueryColumn<Owner, Long> id = nonNullQueryColumn(OWNER_ID);
    private final NullableQueryColumn<Owner, String> name = nullableQueryColumn(OWNER_NAME);

    private OwnerTable() {
      super(OWNER);
    }

    private OwnerTable(Identifier alias) {
      super(OWNER, alias);
    }

    private NonNullQueryColumn<Owner, Long> id() {
      return id;
    }

    private NullableQueryColumn<Owner, String> name() {
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
            DialectFeature.LEFT_JOIN,
            DialectFeature.RIGHT_JOIN,
            DialectFeature.FULL_JOIN,
            DialectFeature.CROSS_JOIN);
    private final SqlRenderer renderer =
        new StandardSqlRenderer(id(), identifierRules(), capabilities);

    @Override
    public String id() {
      return "join-test";
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
