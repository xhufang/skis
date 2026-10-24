package io.skis.query.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.StatementPurpose;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.jdbc.QueryExecutionException;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.mapping.spi.RowReadContext;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.CountQuery;
import io.skis.query.NonNullSelectable;
import io.skis.query.NullPlacement;
import io.skis.query.PageRequest;
import io.skis.query.QueryCondition;
import io.skis.query.QueryOperations;
import io.skis.query.QueryParameter;
import io.skis.query.QueryTable;
import io.skis.query.SelectDescription;
import io.skis.query.SelectQuery;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnSelect;
import io.skis.query.SingleRow;
import io.skis.query.SliceRequest;
import io.skis.query.SortDirection;
import io.skis.query.SortSpecification;
import io.skis.query.Sql;
import io.skis.query.spi.ProjectionMapping;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.OrderDirection;
import io.skis.sql.ast.QueryBlockAnalysis;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
import io.skis.sql.ast.SqlPredicate;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
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

  private static final PropertyMeta<ReadOnlyView, Long> VIEW_VALUE =
      new PropertyMeta<>(0, "value", Long.class, ColumnMeta.of("value", false));
  private static final EntityMeta<ReadOnlyView> READ_ONLY_VIEW =
      EntityMeta.simple(
          ReadOnlyView.class,
          new TableMeta("", "shelter", "read_only_view"),
          List.of(VIEW_VALUE),
          null,
          true);

  private static final PetTable PET_TABLE = new PetTable();
  private static final OwnerTable OWNER_TABLE = new OwnerTable();
  private static final ReadOnlyViewTable VIEW_TABLE = new ReadOnlyViewTable();

  @Test
  void selectsAJoinedEntityFromAnIndependentRootAndKeepsTheOriginalQueryImmutable() {
    QueryOperations operations = operations();
    SelectQuery<Owner> base = operations.select(OWNER_TABLE).from(PET_TABLE);
    SelectQuery<Owner> joined = base.join(OWNER_TABLE).on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()));

    assertNotSame(base, joined);
    assertThrows(
        QueryValidationException.class,
        () -> ((DefaultSelectQuery<Owner>) base).compilation(QueryPagination.None.INSTANCE));
    assertThrows(
        QueryValidationException.class,
        () -> ((DefaultSelectQuery<Owner>) base).countCompilation());

    QueryCompilation<Owner> compilation =
        ((DefaultSelectQuery<Owner>) joined).compilation(QueryPagination.None.INSTANCE);
    assertEquals(
        "SELECT \"owner\".\"id\", \"owner\".\"owner_name\" "
            + "FROM \"shelter\".\"pet\" INNER JOIN \"shelter\".\"owner\" "
            + "ON \"pet\".\"owner_id\" = \"owner\".\"id\"",
        compilation.plan().sql());
  }

  @Test
  void buildsReusableDescriptionsWithoutAnExecutorAndPreservesTheirResultContracts() {
    SelectDescription<Pet> entity = Sql.selectFrom(PET_TABLE);
    SingleColumnSelect<Long> requiredValue =
        Sql.select(PET_TABLE.id())
            .from(PET_TABLE)
            .join(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()))
            .orderBy(PET_TABLE.id().asc())
            .distinct();
    SingleColumnSelect<@Nullable String> nullableValue =
        Sql.select(OWNER_TABLE.name()).from(OWNER_TABLE).where(OWNER_TABLE.name().isNotNull());

    SelectQuery<Pet> entityQuery = operations().query(entity);
    SelectQuery<Long> requiredValueQuery = operations().query(requiredValue);
    SelectQuery<@Nullable String> nullableValueQuery = operations().query(nullableValue);

    assertTrue(entityQuery instanceof DefaultSelectQuery<?>);
    assertTrue(requiredValueQuery instanceof DefaultSelectQuery<?>);
    assertTrue(nullableValueQuery instanceof DefaultSelectQuery<?>);
    String chainedSql =
        ((DefaultSelectQuery<Long>) requiredValueQuery)
            .compilation(QueryPagination.None.INSTANCE)
            .plan()
            .sql();
    assertTrue(chainedSql.contains("SELECT DISTINCT"));
    assertTrue(chainedSql.contains("INNER JOIN"));
    assertTrue(chainedSql.contains("ORDER BY"));
    for (String terminal : List.of("fetchList", "fetchPage", "cursor", "stream", "withOptions")) {
      assertFalse(
          Arrays.stream(SelectDescription.class.getMethods())
              .anyMatch(method -> method.getName().equals(terminal)));
    }
  }

  @Test
  void adaptsExplicitlyBoundDescriptionsThroughTheSameSelectPipelineAndSql() {
    QueryParameter<String> name = Sql.parameter(String.class, "petName");
    SingleColumnSelect<Long> description =
        Sql.select(PET_TABLE.id()).from(PET_TABLE).where(PET_TABLE.name().eq(name));
    QueryOperations operations = operations();
    DefaultSelectQuery<Long> described =
        (DefaultSelectQuery<Long>) operations.query(description).bind(name, "Mimi");
    DefaultSelectQuery<Long> legacy =
        (DefaultSelectQuery<Long>)
            operations.select(PET_TABLE.id()).from(PET_TABLE).where(PET_TABLE.name().eq("Mimi"));

    QueryCompilation<Long> describedCompilation =
        described.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> legacyCompilation = legacy.compilation(QueryPagination.None.INSTANCE);

    assertEquals(legacyCompilation.plan().sql(), describedCompilation.plan().sql());
    assertEquals(List.of("Mimi"), (QueryArguments) describedCompilation.argument());

    QueryParameter<Long> firstId = Sql.parameter(Long.class, "firstId");
    QueryParameter<Long> secondId = Sql.parameter(Long.class, "secondId");
    SingleColumnSelect<Long> membership =
        Sql.select(PET_TABLE.id())
            .from(PET_TABLE)
            .where(PET_TABLE.id().inParameters(List.of(firstId, secondId)));
    DefaultSelectQuery<Long> membershipQuery =
        (DefaultSelectQuery<Long>)
            operations.query(membership).bind(firstId, 1L).bind(secondId, 2L);
    QueryCompilation<Long> membershipCompilation =
        membershipQuery.compilation(QueryPagination.None.INSTANCE);
    assertEquals(List.of(1L, 2L), (QueryArguments) membershipCompilation.argument());
  }

  @Test
  void executesReusableDescriptionsWithFreshBindingsAndTheirDeclaredNullability() {
    QueryParameter<String> petName = Sql.parameter(String.class, "petName");
    SingleColumnSelect<Long> petIdDescription =
        Sql.select(PET_TABLE.id()).from(PET_TABLE).where(PET_TABLE.name().eq(petName));
    QueryParameter<Long> ownerId = Sql.parameter(Long.class, "ownerId");
    SingleColumnSelect<@Nullable String> ownerNameDescription =
        Sql.select(OWNER_TABLE.name()).from(OWNER_TABLE).where(OWNER_TABLE.id().eq(ownerId));
    ScriptedQueryConnectionProvider connections =
        new ScriptedQueryConnectionProvider(
            List.of(
                List.of(Map.of(1, 101L)),
                List.of(Map.of(1, 202L)),
                List.of(Map.<Integer, Object>of()),
                List.of()));
    QueryOperations operations = queryPlanCatalog().bind(new JdbcExecutor(connections));

    DefaultSelectQuery<Long> mimi =
        (DefaultSelectQuery<Long>) operations.query(petIdDescription).bind(petName, "Mimi");
    DefaultSelectQuery<Long> momo =
        (DefaultSelectQuery<Long>) operations.query(petIdDescription).bind(petName, "Momo");

    assertEquals(
        mimi.compilation(QueryPagination.None.INSTANCE).plan().sql(),
        momo.compilation(QueryPagination.None.INSTANCE).plan().sql());
    assertEquals(List.of(101L), mimi.fetchList());
    assertEquals(List.of(202L), momo.fetchList());

    SingleRow<@Nullable String> presentNull =
        operations.query(ownerNameDescription).bind(ownerId, 7L).fetchOne();
    SingleRow<@Nullable String> noRow =
        operations.query(ownerNameDescription).bind(ownerId, 8L).fetchOne();

    assertTrue(presentNull.hasRow() && presentNull.isNull());
    assertTrue(!noRow.hasRow());
    assertEquals(
        List.of(List.of("Mimi"), List.of("Momo"), List.of(7L), List.of(8L)),
        connections.bindings());
    assertEquals(connections.sql().get(0), connections.sql().get(1));
  }

  @Test
  void rejectsCapturedDescriptionValuesAndInvalidParameterEnvironmentsBeforeJdbc() {
    QueryValidationException captured =
        assertThrows(
            QueryValidationException.class,
            () -> Sql.select(PET_TABLE.id()).from(PET_TABLE).where(PET_TABLE.name().eq("Mimi")));
    assertTrue(captured.getMessage().contains("cannot capture ordinary values"));

    QueryParameter<String> used = Sql.parameter(String.class, "used");
    QueryParameter<String> extra = Sql.parameter(String.class, "extra");
    SingleColumnSelect<Long> description =
        Sql.select(PET_TABLE.id()).from(PET_TABLE).where(PET_TABLE.name().eq(used));
    QueryOperations operations = operations();
    DefaultSelectQuery<Long> missing = (DefaultSelectQuery<Long>) operations.query(description);
    assertThrows(
        QueryValidationException.class, () -> missing.compilation(QueryPagination.None.INSTANCE));
    assertThrows(
        QueryValidationException.class,
        () -> operations.query(description).bind(used, "Mimi").bind(extra, "unused"));
  }

  @Test
  void selectsAJoinedScalarFromAnIndependentRoot() {
    SelectQuery<Long> query =
        operations()
            .select(OWNER_TABLE.id())
            .from(PET_TABLE)
            .join(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()));

    QueryCompilation<Long> compilation =
        ((DefaultSelectQuery<Long>) query).compilation(QueryPagination.None.INSTANCE);

    assertEquals(
        "SELECT \"owner\".\"id\" FROM \"shelter\".\"pet\" "
            + "INNER JOIN \"shelter\".\"owner\" ON \"pet\".\"owner_id\" = \"owner\".\"id\"",
        compilation.plan().sql());
  }

  @Test
  void allowsRequiredEntitiesOnOuterJoinNonNullSidesAndCrossJoins() {
    DefaultSelectQuery<Pet> leftRoot =
        (DefaultSelectQuery<Pet>)
            operations()
                .selectFrom(PET_TABLE)
                .leftJoin(OWNER_TABLE)
                .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()));
    DefaultSelectQuery<Owner> rightTarget =
        (DefaultSelectQuery<Owner>)
            operations()
                .select(OWNER_TABLE)
                .from(PET_TABLE)
                .rightJoin(OWNER_TABLE)
                .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()));
    DefaultSelectQuery<Owner> crossTarget =
        (DefaultSelectQuery<Owner>)
            operations().select(OWNER_TABLE).from(PET_TABLE).crossJoin(OWNER_TABLE);

    leftRoot.compilation(QueryPagination.None.INSTANCE);
    rightTarget.compilation(QueryPagination.None.INSTANCE);
    crossTarget.compilation(QueryPagination.None.INSTANCE);
  }

  @Test
  void rejectsRequiredResultsOnTheFinalNullExtendedSideBeforeJdbc() {
    SelectQuery<Owner> requiredOwner =
        operations()
            .select(OWNER_TABLE)
            .from(PET_TABLE)
            .leftJoin(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()));
    QueryValidationException ownerFailure =
        assertThrows(
            QueryValidationException.class,
            () ->
                ((DefaultSelectQuery<Owner>) requiredOwner)
                    .compilation(QueryPagination.None.INSTANCE));

    assertTrue(ownerFailure.getMessage().contains("null-extended table occurrence #1"));
    assertTrue(ownerFailure.getMessage().contains("selectNullable(table)"));

    SelectQuery<Long> requiredOwnerId =
        operations()
            .select(OWNER_TABLE.id())
            .from(PET_TABLE)
            .leftJoin(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()));
    QueryValidationException scalarFailure =
        assertThrows(
            QueryValidationException.class,
            () ->
                ((DefaultSelectQuery<Long>) requiredOwnerId)
                    .compilation(QueryPagination.None.INSTANCE));
    assertTrue(scalarFailure.getMessage().contains("selectNullable(selectable)"));

    SelectQuery<Pet> requiredRoot =
        operations()
            .selectFrom(PET_TABLE)
            .rightJoin(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()));
    QueryValidationException rootFailure =
        assertThrows(
            QueryValidationException.class,
            () ->
                ((DefaultSelectQuery<Pet>) requiredRoot)
                    .compilation(QueryPagination.None.INSTANCE));
    assertTrue(rootFailure.getMessage().contains("null-extended table occurrence #0"));
  }

  @Test
  void compilesNullableJoinedEntityAndScalarResultsWithTheirSelectedRuntimeModel()
      throws Exception {
    QueryPlanCatalog catalog = queryPlanCatalog();
    QueryCondition on = PET_TABLE.ownerId().eq(OWNER_TABLE.id());
    QueryCompilation<Owner> ownerCompilation =
        compileSelection(
            catalog,
            PET_TABLE,
            SelectedResult.nullableEntity(OWNER_TABLE),
            List.of(
                new QueryJoin(io.skis.sql.ast.JoinType.LEFT, QueryRelations.of(OWNER_TABLE), on)));

    assertEquals(
        "SELECT \"owner\".\"id\", \"owner\".\"owner_name\" "
            + "FROM \"shelter\".\"pet\" LEFT JOIN \"shelter\".\"owner\" "
            + "ON \"pet\".\"owner_id\" = \"owner\".\"id\"",
        ownerCompilation.plan().sql());
    assertNull(
        ownerCompilation.plan().rowDecoder().decode(resultSet(Map.of()), RowReadContext.EMPTY));
    assertEquals(
        new Owner(2L, "Ada"),
        ownerCompilation
            .plan()
            .rowDecoder()
            .decode(resultSet(Map.of(1, 2L, 2, "Ada")), RowReadContext.EMPTY));

    QueryCompilation<Long> idCompilation =
        compileSelection(
            catalog,
            PET_TABLE,
            SelectedResult.nullableScalar(OWNER_TABLE.id()),
            List.of(
                new QueryJoin(io.skis.sql.ast.JoinType.LEFT, QueryRelations.of(OWNER_TABLE), on)));
    assertNull(idCompilation.plan().rowDecoder().decode(resultSet(Map.of()), RowReadContext.EMPTY));
    assertEquals(
        2L,
        idCompilation.plan().rowDecoder().decode(resultSet(Map.of(1, 2L)), RowReadContext.EMPTY));
  }

  @Test
  void rejectsNullableEntityWithoutPrimaryKeyDuringQueryCompilation() {
    CountQuery countQuery = operations().selectNullable(VIEW_TABLE).from(VIEW_TABLE).countQuery();

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class, () -> ((DefaultCountQuery) countQuery).compilation());

    assertTrue(failure.getMessage().contains("requires complete non-null primary-key metadata"));
    assertTrue(failure.getMessage().contains("table occurrence #0"));
  }

  @Test
  void keysetUsesOuterJoinEffectiveNullabilityForPhysicalNonNullColumns() {
    SelectQuery<@Nullable Pet> query =
        operations()
            .selectNullable(PET_TABLE)
            .from(PET_TABLE)
            .rightJoin(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()))
            .orderBy(PET_TABLE.id().asc(), OWNER_TABLE.id().asc());

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class, () -> query.fetchSlice(SliceRequest.keysetFirst(10)));

    assertTrue(failure.getMessage().contains("effectively nullable keyset ordering property 'id'"));
  }

  @Test
  void ordersByFinalJoinColumnsAndRequiresEveryOccurrencePrimaryKeyForPagination() {
    SelectQuery<Pet> stable =
        operations()
            .selectFrom(PET_TABLE)
            .orderBy(OWNER_TABLE.id().asc(), PET_TABLE.id().asc())
            .join(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()));

    QueryCompilation<Pet> compilation =
        ((DefaultSelectQuery<Pet>) stable).compilation(new QueryPagination.Offset(11, 20));
    assertTrue(
        compilation.plan().sql().contains("ORDER BY \"owner\".\"id\" ASC, \"pet\".\"id\" ASC"));

    SelectQuery<Pet> invisible = operations().selectFrom(PET_TABLE).orderBy(OWNER_TABLE.id().asc());
    assertThrows(
        QueryValidationException.class,
        () -> ((DefaultSelectQuery<Pet>) invisible).compilation(QueryPagination.None.INSTANCE));

    SelectQuery<Pet> rootOnly =
        operations()
            .selectFrom(PET_TABLE)
            .join(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()))
            .thenByPrimaryKey(SortDirection.ASC);
    QueryValidationException missingJoinedKey =
        assertThrows(
            QueryValidationException.class, () -> rootOnly.fetchSlice(SliceRequest.offset(0, 10)));
    assertTrue(missingJoinedKey.getMessage().contains("table occurrence #1"));
    assertTrue(missingJoinedKey.getMessage().contains("effective qualifier 'owner'"));
    assertTrue(missingJoinedKey.getMessage().contains("missing primary-key property 'id'"));

    SelectQuery<Pet> missingMetadata =
        operations().selectFrom(PET_TABLE).crossJoin(VIEW_TABLE).orderBy(PET_TABLE.id().asc());
    QueryValidationException missingViewKey =
        assertThrows(
            QueryValidationException.class,
            () -> missingMetadata.fetchPage(PageRequest.page(0, 10)));
    assertTrue(missingViewKey.getMessage().contains("table occurrence #1"));
    assertTrue(missingViewKey.getMessage().contains("effective qualifier 'read_only_view'"));
    assertTrue(missingViewKey.getMessage().contains("requires primary-key metadata"));

    AssertionError reachedJdbc =
        assertThrows(AssertionError.class, () -> stable.fetchSlice(SliceRequest.offset(0, 10)));
    assertEquals("query compilation must not acquire JDBC", reachedJdbc.getMessage());
  }

  @Test
  void replacingOrderingDoesNotReuseAStructurallyEqualForeignTableOccurrence() {
    OwnerTable joinedOwner = OWNER_TABLE.as("ordered_owner");
    OwnerTable foreignOwner = OWNER_TABLE.as("ordered_owner");
    SelectQuery<Pet> original =
        operations()
            .selectFrom(PET_TABLE)
            .join(joinedOwner)
            .on(PET_TABLE.ownerId().eq(joinedOwner.id()))
            .orderBy(joinedOwner.id().asc(), PET_TABLE.id().asc());

    SelectQuery<Pet> replaced = original.orderBy(foreignOwner.id().asc(), PET_TABLE.id().asc());

    assertNotSame(original, replaced);
    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () -> ((DefaultSelectQuery<Pet>) replaced).compilation(QueryPagination.None.INSTANCE));
    assertEquals(SkisErrorCode.QUERY_COMPILED_SHAPE, failure.code());
    assertTrue(failure.getCause().getMessage().contains("$ ORDER BY item #0"));
    assertTrue(failure.getCause().getMessage().contains("ordered_owner.id"));
    assertTrue(failure.getCause().getMessage().contains("unresolved outer reference"));
    assertTrue(
        failure
            .getCause()
            .getMessage()
            .contains("table references are matched by object identity"));
  }

  @Test
  void distinctPaginationUsesTheSelectedTupleInsteadOfJoinOccurrenceKeys() {
    SelectQuery<String> stableDistinct =
        operations()
            .select(PET_TABLE.name())
            .from(PET_TABLE)
            .crossJoin(VIEW_TABLE)
            .distinct()
            .orderBy(PET_TABLE.name().asc());

    AssertionError reachedJdbc =
        assertThrows(
            AssertionError.class, () -> stableDistinct.fetchSlice(SliceRequest.offset(0, 10)));
    assertEquals("query compilation must not acquire JDBC", reachedJdbc.getMessage());

    SelectQuery<Pet> incompleteTuple =
        operations()
            .selectFrom(PET_TABLE)
            .crossJoin(VIEW_TABLE)
            .distinct()
            .orderBy(PET_TABLE.id().asc());
    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () -> incompleteTuple.fetchSlice(SliceRequest.offset(0, 10)));
    assertTrue(failure.getMessage().contains("must cover every selected expression"));
    assertTrue(failure.getMessage().contains("pet.ownerId"));
  }

  @Test
  void complexDistinctJoinRequiresExplicitCountButStillAllowsSlice() {
    ProjectionMapping<Object> tuple =
        ProjectionMapping.generated(
            GeneratedModelAbi.CURRENT,
            Object.class,
            "pet-owner-tuple",
            List.of(
                new ProjectionMapping.Parameter(0, "petId", Long.class, Nullability.NON_NULL, 0),
                new ProjectionMapping.Parameter(
                    1, "ownerName", String.class, Nullability.NULLABLE, 1)),
            readers -> (resultSet, context) -> new Object());
    SelectQuery<Object> query =
        operations()
            .select(tuple.bind(PET_TABLE.id(), OWNER_TABLE.name()))
            .from(PET_TABLE)
            .join(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()))
            .distinct()
            .orderBy(PET_TABLE.id().asc(), OWNER_TABLE.name().asc());

    QueryValidationException countFailure =
        assertThrows(
            QueryValidationException.class,
            () -> ((DefaultCountQuery) query.countQuery()).compilation());
    assertTrue(countFailure.getMessage().contains("multi-expression distinct result"));
    assertTrue(countFailure.getMessage().contains("explicit count query"));

    AssertionError reachedJdbc =
        assertThrows(AssertionError.class, () -> query.fetchSlice(SliceRequest.offset(0, 10)));
    assertEquals("query compilation must not acquire JDBC", reachedJdbc.getMessage());
  }

  @Test
  void joinCountsPreserveRowsDistinctEntitiesAndNullableEntityAbsence() {
    QueryCondition on = PET_TABLE.ownerId().eq(OWNER_TABLE.id());
    SelectQuery<Pet> joined = operations().selectFrom(PET_TABLE).join(OWNER_TABLE).on(on);
    String rowCount = ((DefaultCountQuery) joined.countQuery()).compilation().plan().sql();
    assertEquals(
        "SELECT COUNT(*) FROM \"shelter\".\"pet\" INNER JOIN \"shelter\".\"owner\" "
            + "ON \"pet\".\"owner_id\" = \"owner\".\"id\"",
        rowCount);

    String rootEntityCount =
        ((DefaultCountQuery) joined.distinct().countQuery()).compilation().plan().sql();
    assertEquals(
        "SELECT COUNT(DISTINCT \"pet\".\"id\") FROM \"shelter\".\"pet\" "
            + "INNER JOIN \"shelter\".\"owner\" "
            + "ON \"pet\".\"owner_id\" = \"owner\".\"id\"",
        rootEntityCount);

    SelectQuery<@Nullable Owner> nullableOwner =
        operations()
            .selectNullable(OWNER_TABLE)
            .from(PET_TABLE)
            .leftJoin(OWNER_TABLE)
            .on(on)
            .distinct();
    String nullableOwnerCount =
        ((DefaultCountQuery) nullableOwner.countQuery()).compilation().plan().sql();
    assertEquals(
        "SELECT COUNT(DISTINCT \"owner\".\"id\") "
            + "+ CASE WHEN COUNT(*) > COUNT(\"owner\".\"id\") THEN 1 ELSE 0 END "
            + "FROM \"shelter\".\"pet\" LEFT JOIN \"shelter\".\"owner\" "
            + "ON \"pet\".\"owner_id\" = \"owner\".\"id\"",
        nullableOwnerCount);
  }

  @Test
  void joinFingerprintsSeparateStructureAndOccurrenceButNotParameterValues() {
    OwnerTable primary = OWNER_TABLE.as("primary_owner");
    OwnerTable secondary = OWNER_TABLE.as("secondary_owner");
    DefaultSelectQuery<Pet> ada =
        (DefaultSelectQuery<Pet>)
            operations()
                .selectFrom(PET_TABLE)
                .join(primary)
                .on(PET_TABLE.ownerId().eq(primary.id()).and(primary.name().eq("Ada")))
                .orderBy(PET_TABLE.id().asc(), primary.id().asc());
    DefaultSelectQuery<Pet> grace =
        (DefaultSelectQuery<Pet>)
            operations()
                .selectFrom(PET_TABLE)
                .join(primary)
                .on(PET_TABLE.ownerId().eq(primary.id()).and(primary.name().eq("Grace")))
                .orderBy(PET_TABLE.id().asc(), primary.id().asc());
    DefaultSelectQuery<Pet> left =
        (DefaultSelectQuery<Pet>)
            operations()
                .selectFrom(PET_TABLE)
                .leftJoin(primary)
                .on(PET_TABLE.ownerId().eq(primary.id()).and(primary.name().eq("Ada")))
                .orderBy(PET_TABLE.id().asc(), primary.id().asc());
    DefaultSelectQuery<Pet> secondOccurrence =
        (DefaultSelectQuery<Pet>)
            operations()
                .selectFrom(PET_TABLE)
                .join(primary)
                .on(PET_TABLE.ownerId().eq(primary.id()))
                .join(secondary)
                .on(primary.id().eq(secondary.id()))
                .orderBy(secondary.id().asc());
    DefaultSelectQuery<Pet> firstOccurrence =
        (DefaultSelectQuery<Pet>)
            operations()
                .selectFrom(PET_TABLE)
                .join(primary)
                .on(PET_TABLE.ownerId().eq(primary.id()))
                .join(secondary)
                .on(primary.id().eq(secondary.id()))
                .orderBy(primary.id().asc());

    assertEquals(ada.queryFingerprint(), grace.queryFingerprint());
    assertNotEquals(ada.queryFingerprint(), left.queryFingerprint());
    assertNotEquals(firstOccurrence.orderSignature(), secondOccurrence.orderSignature());
  }

  @Test
  void compilesOnThenWhereParametersWithOneDenseStatementOrder() throws Exception {
    QueryCondition on = PET_TABLE.ownerId().eq(OWNER_TABLE.id()).and(OWNER_TABLE.name().eq("Ada"));
    SelectQuery<Pet> query =
        operations().selectFrom(PET_TABLE).join(OWNER_TABLE).on(on).where(PET_TABLE.id().gt(10L));

    QueryCompilation<Pet> compilation =
        ((DefaultSelectQuery<Pet>) query).compilation(QueryPagination.None.INSTANCE);

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"owner_id\", \"pet\".\"pet_name\" "
            + "FROM \"shelter\".\"pet\" INNER JOIN \"shelter\".\"owner\" "
            + "ON \"pet\".\"owner_id\" = \"owner\".\"id\" AND \"owner\".\"owner_name\" = ? "
            + "WHERE \"pet\".\"id\" > ?",
        compilation.plan().sql());
    assertEquals(List.of("Ada", 10L), (QueryArguments) compilation.argument());
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
    assertEquals(List.of(List.of("setString", 3, "Ada"), List.of("setLong", 4, 10L)), bindings);
  }

  @Test
  void resolvesEveryAliasOccurrenceAndKeepsMultiJoinParameterOrder() {
    OwnerTable reviewer = OWNER_TABLE.as("reviewer");
    QueryCondition firstOn =
        PET_TABLE.ownerId().eq(OWNER_TABLE.id()).and(OWNER_TABLE.name().eq("Ada"));
    QueryCondition secondOn = OWNER_TABLE.id().eq(reviewer.id()).and(reviewer.name().eq("Grace"));
    DefaultSelectQuery<Pet> query =
        (DefaultSelectQuery<Pet>)
            operations()
                .selectFrom(PET_TABLE)
                .join(OWNER_TABLE)
                .on(firstOn)
                .join(reviewer)
                .on(secondOn)
                .where(PET_TABLE.name().eq("Mimi"));

    QueryCompilation<Pet> compilation = query.compilation(QueryPagination.None.INSTANCE);
    CompiledQueryStructure structure =
        QueryTestSupport.compile(
            PET_TABLE,
            List.of(
                new QueryJoin(
                    io.skis.sql.ast.JoinType.INNER, QueryRelations.of(OWNER_TABLE), firstOn),
                new QueryJoin(
                    io.skis.sql.ast.JoinType.INNER, QueryRelations.of(reviewer), secondOn)),
            PET_TABLE.name().eq("Mimi"));
    EntityRuntimeRegistry registry =
        EntityRuntimeRegistry.of(List.of(petModel(), ownerModel(), readOnlyViewModel()));
    TableRuntimeScope scope = TableRuntimeScope.resolve(registry, structure.fromClause());

    assertEquals(
        List.of(0, 1, 2),
        structure.fromClause().occurrences().stream()
            .map(io.skis.sql.ast.TableOccurrence::occurrenceOrdinal)
            .toList());
    assertSame(scope.require(OWNER_TABLE).model(), scope.require(reviewer).model());
    assertEquals(List.of("Ada", "Grace", "Mimi"), (QueryArguments) compilation.argument());
    assertEquals(
        List.of(0, 1, 2),
        compilation.plan().renderedSql().parameters().stream()
            .map(slot -> slot.ordinal())
            .toList());
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
            .selectNullable(PET_TABLE)
            .from(PET_TABLE)
            .rightJoin(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id())),
        " RIGHT JOIN ");
    assertJoinSql(
        operations()
            .selectNullable(PET_TABLE)
            .from(PET_TABLE)
            .fullJoin(OWNER_TABLE)
            .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id())),
        " FULL JOIN ");
    assertJoinSql(operations().selectFrom(PET_TABLE).crossJoin(OWNER_TABLE), " CROSS JOIN ");
  }

  @Test
  void validatesOnAndWhereAgainstTheirActualTableReferenceScope() {
    OwnerTable reviewer = OWNER_TABLE.as("reviewer");
    SelectQuery<Pet> futureReference =
        operations()
            .selectFrom(PET_TABLE)
            .join(OWNER_TABLE)
            .on(OWNER_TABLE.id().eq(reviewer.id()))
            .join(reviewer)
            .on(OWNER_TABLE.id().eq(reviewer.id()));
    SelectQuery<Pet> invisibleWhere =
        operations().selectFrom(PET_TABLE).where(OWNER_TABLE.id().isNotNull());

    assertThrows(
        QueryValidationException.class,
        () ->
            ((DefaultSelectQuery<Pet>) futureReference).compilation(QueryPagination.None.INSTANCE));
    assertThrows(
        QueryValidationException.class,
        () ->
            ((DefaultSelectQuery<Pet>) invisibleWhere).compilation(QueryPagination.None.INSTANCE));
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
      assertTrue(compiled.parameterSources().isEmpty());
    }

    ComparisonPredicate<?> nullable =
        (ComparisonPredicate<?>) compileCondition(PET_TABLE.name().eq(OWNER_TABLE.name())).ast();
    assertEquals(Nullability.NULLABLE, nullable.nullability());
  }

  @Test
  void frameworkExpressionSelectableUsesSharedPredicatesOrderingAndFailsClosedForMapping() {
    ExpressionSelectable<Long> nullable =
        new ExpressionSelectable<>(LiteralExpression.nullLiteral(Long.class));
    ExpressionSelectable<Long> zero =
        new ExpressionSelectable<>(LiteralExpression.zero(Long.class));
    NonNullExpressionSelectable<Long> one =
        new NonNullExpressionSelectable<>(LiteralExpression.one(Long.class));

    ConditionCompilation valueCompiled = compileCondition(one.ge(1L));
    ComparisonPredicate<?> valueComparison = (ComparisonPredicate<?>) valueCompiled.ast();
    assertSame(one.expression(), valueComparison.left());
    assertEquals(List.of(one), valueCompiled.parameterSources());
    assertEquals(List.of(1L), valueCompiled.arguments());

    ConditionCompilation expressionCompiled = compileCondition(zero.eq(one));
    ComparisonPredicate<?> expressionComparison = (ComparisonPredicate<?>) expressionCompiled.ast();
    assertSame(zero.expression(), expressionComparison.left());
    assertSame(one.expression(), expressionComparison.right());
    assertTrue(expressionCompiled.parameterSources().isEmpty());
    assertTrue(expressionCompiled.arguments().isEmpty());

    NullPredicate nullCheck = (NullPredicate) compileCondition(nullable.isNull()).ast();
    assertSame(nullable.expression(), nullCheck.operand());

    SortSpecification ordering = zero.desc().nullsFirst();
    assertSame(zero, QueryModels.sort(ordering).selectable());
    assertSame(zero.expression(), QueryModels.sort(ordering).expression());
    assertEquals(SortDirection.DESC, ordering.direction());
    assertEquals(NullPlacement.FIRST, ordering.nullPlacement());
    assertEquals(OrderDirection.DESC, QueryModels.sort(ordering).ast().direction());
    assertEquals(NullOrder.FIRST, QueryModels.sort(ordering).ast().nullOrder());

    TableRuntimeScope scope =
        TableRuntimeScope.resolve(
            EntityRuntimeRegistry.of(List.of(petModel())),
            io.skis.sql.ast.FromClause.of(QueryTableModel.of(PET_TABLE).tableExpression()));
    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class, () -> ResolvedValueMapping.resolve(one, scope));
    assertEquals(SkisErrorCode.QUERY_RESULT_MAPPING, failure.code());
    assertEquals(
        "no query-local value mapping is registered for framework expression 'LiteralExpression'",
        failure.diagnostics().facts().detail());
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
  void resolvedStructureAndScopeDiagnosticsNeverContainBoundValues() {
    String sensitiveValue = "8675309";
    QueryCondition validCondition = PET_TABLE.name().eq(sensitiveValue);
    CompiledQueryStructure validStructure =
        QueryTestSupport.compile(PET_TABLE, List.of(), validCondition);
    SelectStatement validStatement =
        new SelectStatement(
            List.of(SelectableSupport.expression(PET_TABLE.id())),
            validStructure.fromClause(),
            validStructure.where());

    assertEquals(List.of(sensitiveValue), QueryTestSupport.arguments(validStructure));
    QueryBlockAnalysis analysis = SemanticValidator.analyzeComplete(validStatement);
    assertFalse(analysis.structureKey().canonicalForm().contains(sensitiveValue));

    OwnerTable invisible = OWNER_TABLE.as("invisible_owner");
    QueryCondition invalidCondition =
        PET_TABLE.name().eq(sensitiveValue).and(PET_TABLE.ownerId().eq(invisible.id()));
    CompiledQueryStructure invalidStructure =
        QueryTestSupport.compile(PET_TABLE, List.of(), invalidCondition);
    assertEquals(List.of(sensitiveValue), QueryTestSupport.arguments(invalidStructure));
    EntityPlanSet<Pet> plans = queryPlanCatalog().require(PET);

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () ->
                plans
                    .compiler()
                    .compileQuery(
                        plans.model(),
                        PET_TABLE,
                        invalidStructure,
                        null,
                        StatementPurpose.PRIMARY));

    assertEquals(SkisErrorCode.QUERY_COMPILED_SHAPE, failure.code());
    assertTrue(failure.getCause().getMessage().contains("unresolved outer reference"));
    assertFalse(failure.getMessage().contains(sensitiveValue));
    assertFalse(String.valueOf(failure.getCause()).contains(sensitiveValue));
  }

  @Test
  void reusesJoinPlanAndCountAcrossCacheHits() {
    @SuppressWarnings("unchecked")
    DefaultSelectQuery<Pet> query =
        (DefaultSelectQuery<Pet>)
            operations()
                .selectFrom(PET_TABLE)
                .join(OWNER_TABLE)
                .on(PET_TABLE.ownerId().eq(OWNER_TABLE.id()).and(OWNER_TABLE.name().eq("Ada")))
                .where(PET_TABLE.name().eq("Mimi"));

    QueryCompilation<Pet> first = query.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Pet> second = query.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> firstCount = query.countCompilation();
    QueryCompilation<Long> secondCount = query.countCompilation();

    assertSame(first.plan(), second.plan());
    assertSame(first.ast(), second.ast());
    assertSame(first.argument(), second.argument());
    assertEquals(List.of("Ada", "Mimi"), (QueryArguments) first.argument());
    assertSame(firstCount.plan(), secondCount.plan());
    assertSame(firstCount.ast(), secondCount.ast());
    assertSame(firstCount.argument(), secondCount.argument());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void rejectsRawColumnComparisonsThatBypassTheGenericJavaTypeCheck() {
    Selectable ownerId = PET_TABLE.ownerId();
    Selectable ownerName = OWNER_TABLE.name();

    assertThrows(QueryValidationException.class, () -> ownerId.eq(ownerName));
  }

  @Test
  void embedsCorrelatedExistsWithOneStatementWideParameterLayout() {
    PetTable innerPet = PET_TABLE.as("exists_pet");
    QueryParameter<Long> outerId = Sql.parameter(Long.class, "outerId");
    QueryParameter<String> innerName = Sql.parameter(String.class, "innerName");
    SingleColumnSelect<Long> matchingPets =
        Sql.select(innerPet.id())
            .from(innerPet)
            .where(innerPet.ownerId().eq(PET_TABLE.id()).and(innerPet.name().eq(innerName)));
    SelectDescription<Pet> description =
        Sql.selectFrom(PET_TABLE).where(PET_TABLE.id().eq(outerId).and(Sql.exists(matchingPets)));
    DefaultSelectQuery<Pet> query =
        (DefaultSelectQuery<Pet>)
            operations().query(description).bind(outerId, 7L).bind(innerName, "Mimi");

    QueryCompilation<Pet> compilation = query.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> count = query.countCompilation();

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"owner_id\", \"pet\".\"pet_name\" "
            + "FROM \"shelter\".\"pet\" WHERE \"pet\".\"id\" = ? AND EXISTS ("
            + "SELECT \"exists_pet\".\"id\" FROM \"shelter\".\"pet\" AS \"exists_pet\" "
            + "WHERE \"exists_pet\".\"owner_id\" = \"pet\".\"id\" "
            + "AND \"exists_pet\".\"pet_name\" = ?)",
        compilation.plan().sql());
    assertEquals(List.of(7L, "Mimi"), (QueryArguments) compilation.argument());
    assertEquals(
        List.of(0, 1),
        compilation.plan().renderedSql().parameters().stream()
            .map(io.skis.sql.ast.ParameterSlot::ordinal)
            .toList());
    assertTrue(count.plan().sql().startsWith("SELECT COUNT(*)"));
    assertTrue(count.plan().sql().contains("EXISTS ("));
    assertEquals(List.of(7L, "Mimi"), (QueryArguments) count.argument());
  }

  @Test
  void embedsCorrelatedInSubqueryWithOneStatementWideParameterLayout() {
    PetTable innerPet = PET_TABLE.as("membership_pet");
    QueryParameter<Long> outerId = Sql.parameter(Long.class, "outerId");
    QueryParameter<String> innerName = Sql.parameter(String.class, "innerName");
    SingleColumnSelect<Long> ownerIds =
        Sql.select(innerPet.ownerId())
            .from(innerPet)
            .where(innerPet.id().eq(PET_TABLE.id()).and(innerPet.name().eq(innerName)))
            .orderBy(innerPet.id().asc());
    SelectDescription<Pet> description =
        Sql.selectFrom(PET_TABLE)
            .where(PET_TABLE.id().eq(outerId).and(PET_TABLE.ownerId().in(ownerIds)));
    DefaultSelectQuery<Pet> query =
        (DefaultSelectQuery<Pet>)
            operations().query(description).bind(outerId, 7L).bind(innerName, "Mimi");

    QueryCompilation<Pet> compilation = query.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> count = query.countCompilation();
    QueryBlockAnalysis analysis =
        SemanticValidator.analyzeComplete((SelectStatement) compilation.ast());

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"owner_id\", \"pet\".\"pet_name\" "
            + "FROM \"shelter\".\"pet\" WHERE \"pet\".\"id\" = ? AND "
            + "\"pet\".\"owner_id\" IN (SELECT \"membership_pet\".\"owner_id\" "
            + "FROM \"shelter\".\"pet\" AS \"membership_pet\" WHERE "
            + "\"membership_pet\".\"id\" = \"pet\".\"id\" AND "
            + "\"membership_pet\".\"pet_name\" = ? "
            + "ORDER BY \"membership_pet\".\"id\" ASC)",
        compilation.plan().sql());
    assertEquals(List.of(7L, "Mimi"), (QueryArguments) compilation.argument());
    assertEquals(
        List.of(0, 1),
        compilation.plan().renderedSql().parameters().stream()
            .map(io.skis.sql.ast.ParameterSlot::ordinal)
            .toList());
    assertEquals(1, analysis.nestedBlocks().size());
    assertEquals(
        QueryBlockAnalysis.NestedQueryKind.IN_SUBQUERY, analysis.nestedBlocks().getFirst().kind());
    assertTrue(analysis.nestedBlocks().getFirst().analysis().correlated());
    assertTrue(count.plan().sql().contains(" IN (SELECT "));
    assertEquals(List.of(7L, "Mimi"), (QueryArguments) count.argument());
  }

  @Test
  void embedsScalarSubqueriesAcrossValuePositionsAndProjectsCountParameters() throws Exception {
    OwnerTable innerOwner = OWNER_TABLE.as("scalar_owner");
    QueryParameter<String> ownerName = Sql.parameter(String.class, "ownerName");
    SingleColumnSelect<Long> ownerId =
        Sql.select(innerOwner.id())
            .from(innerOwner)
            .where(innerOwner.id().eq(PET_TABLE.ownerId()).and(innerOwner.name().eq(ownerName)));
    Selectable<Long> scalar = Sql.scalar(ownerId);
    SingleColumnSelect<Long> description =
        Sql.select(scalar)
            .from(PET_TABLE)
            .where(scalar.isNotNull())
            .orderBy(scalar.desc().nullsLast());
    DefaultSelectQuery<Long> query =
        (DefaultSelectQuery<Long>) operations().query(description).bind(ownerName, "Ada");

    QueryCompilation<Long> compilation = query.compilation(QueryPagination.None.INSTANCE);
    QueryCompilation<Long> count = query.countCompilation();
    QueryBlockAnalysis analysis =
        SemanticValidator.analyzeComplete((SelectStatement) compilation.ast());

    assertEquals(Nullability.NULLABLE, scalar.nullability());
    assertFalse(scalar instanceof NonNullSelectable<?>);
    assertEquals(
        "SELECT (SELECT \"scalar_owner\".\"id\" FROM \"shelter\".\"owner\" AS "
            + "\"scalar_owner\" WHERE \"scalar_owner\".\"id\" = \"pet\".\"owner_id\" "
            + "AND \"scalar_owner\".\"owner_name\" = ?) FROM \"shelter\".\"pet\" WHERE "
            + "(SELECT \"scalar_owner\".\"id\" FROM \"shelter\".\"owner\" AS "
            + "\"scalar_owner\" WHERE \"scalar_owner\".\"id\" = \"pet\".\"owner_id\" "
            + "AND \"scalar_owner\".\"owner_name\" = ?) IS NOT NULL ORDER BY "
            + "(SELECT \"scalar_owner\".\"id\" FROM \"shelter\".\"owner\" AS "
            + "\"scalar_owner\" WHERE \"scalar_owner\".\"id\" = \"pet\".\"owner_id\" "
            + "AND \"scalar_owner\".\"owner_name\" = ?) DESC NULLS LAST",
        compilation.plan().sql());
    assertEquals(List.of("Ada", "Ada", "Ada"), (QueryArguments) compilation.argument());
    assertEquals(
        List.of(0, 1, 2),
        compilation.plan().renderedSql().parameters().stream()
            .map(io.skis.sql.ast.ParameterSlot::ordinal)
            .toList());
    assertEquals(3, analysis.nestedBlocks().size());
    assertTrue(
        analysis.nestedBlocks().stream()
            .allMatch(
                nested ->
                    nested.kind() == QueryBlockAnalysis.NestedQueryKind.SCALAR_SUBQUERY
                        && nested.analysis().correlated()));
    assertEquals(
        "SELECT COUNT(*) FROM \"shelter\".\"pet\" WHERE (SELECT "
            + "\"scalar_owner\".\"id\" FROM \"shelter\".\"owner\" AS \"scalar_owner\" "
            + "WHERE \"scalar_owner\".\"id\" = \"pet\".\"owner_id\" AND "
            + "\"scalar_owner\".\"owner_name\" = ?) IS NOT NULL",
        count.plan().sql());
    assertEquals(List.of("Ada"), (QueryArguments) count.argument());
    assertEquals(
        9L, compilation.plan().rowDecoder().decode(resultSet(Map.of(1, 9L)), RowReadContext.EMPTY));
    assertNull(compilation.plan().rowDecoder().decode(resultSet(Map.of()), RowReadContext.EMPTY));
  }

  @Test
  void comparesScalarSubqueriesWithoutAddingAnImplicitLimit() {
    OwnerTable innerOwner = OWNER_TABLE.as("scalar_owner");
    Selectable<Long> scalar =
        Sql.scalar(
            Sql.select(innerOwner.id())
                .from(innerOwner)
                .where(innerOwner.id().eq(PET_TABLE.ownerId())));
    SelectDescription<Pet> description =
        Sql.selectFrom(PET_TABLE).where(scalar.eq(PET_TABLE.ownerId()));
    DefaultSelectQuery<Pet> query = (DefaultSelectQuery<Pet>) operations().query(description);

    QueryCompilation<Pet> compilation = query.compilation(QueryPagination.None.INSTANCE);

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"owner_id\", \"pet\".\"pet_name\" "
            + "FROM \"shelter\".\"pet\" WHERE (SELECT \"scalar_owner\".\"id\" "
            + "FROM \"shelter\".\"owner\" AS \"scalar_owner\" WHERE "
            + "\"scalar_owner\".\"id\" = \"pet\".\"owner_id\") = \"pet\".\"owner_id\"",
        compilation.plan().sql());
    assertFalse(compilation.plan().sql().contains("LIMIT"));
  }

  @Test
  void validatesScalarCountSourcesBeforeRemovingTheirSelectionsAndParameters() {
    OwnerTable innerOwner = OWNER_TABLE.as("scalar_owner");
    OwnerTable invisible = OWNER_TABLE.as("invisible_owner");
    QueryParameter<String> name = Sql.parameter(String.class, "name");
    Selectable<Long> invalid =
        Sql.scalar(Sql.select(invisible.id()).from(innerOwner).where(innerOwner.name().eq(name)));
    CountQuery count =
        operations().query(Sql.select(invalid).from(PET_TABLE)).bind(name, "Ada").countQuery();

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class, () -> ((DefaultCountQuery) count).compilation());

    assertEquals(SkisErrorCode.QUERY_COMPILED_SHAPE, failure.code());
    assertTrue(
        failure.getCause().getMessage().contains("not visible in the current or any ancestor"));
  }

  @Test
  void prunesEmptyScalarMembershipParametersAndDenselyRebindsTheRemainingValues() {
    OwnerTable innerOwner = OWNER_TABLE.as("scalar_owner");
    QueryParameter<String> name = Sql.parameter(String.class, "name");
    QueryParameter<Long> petId = Sql.parameter(Long.class, "petId");
    Selectable<Long> scalar =
        Sql.scalar(Sql.select(innerOwner.id()).from(innerOwner).where(innerOwner.name().eq(name)));
    for (boolean negated : List.of(false, true)) {
      QueryCondition membership = negated ? scalar.notIn(List.of()) : scalar.in(List.of());
      SelectDescription<Pet> description =
          Sql.selectFrom(PET_TABLE).where(membership.and(PET_TABLE.id().eq(petId)));
      DefaultSelectQuery<Pet> query =
          (DefaultSelectQuery<Pet>)
              operations().query(description).bind(name, "Ada").bind(petId, 7L);

      QueryCompilation<Pet> content = query.compilation(QueryPagination.None.INSTANCE);
      QueryCompilation<Long> count = query.countCompilation();
      String predicate = (negated ? "TRUE" : "FALSE") + " AND \"pet\".\"id\" = ?";
      assertEquals(
          "SELECT \"pet\".\"id\", \"pet\".\"owner_id\", \"pet\".\"pet_name\" "
              + "FROM \"shelter\".\"pet\" WHERE "
              + predicate,
          content.plan().sql());
      assertEquals(
          "SELECT COUNT(*) FROM \"shelter\".\"pet\" WHERE " + predicate, count.plan().sql());
      assertEquals(List.of(7L), (QueryArguments) content.argument());
      assertEquals(List.of(7L), (QueryArguments) count.argument());
      assertEquals(
          List.of(0),
          content.plan().renderedSql().parameters().stream()
              .map(io.skis.sql.ast.ParameterSlot::ordinal)
              .toList());
      assertSame(count.argument(), query.countCompilation().argument());

      ScriptedQueryConnectionProvider connections =
          new ScriptedQueryConnectionProvider(List.of(List.of()));
      queryPlanCatalog()
          .bind(new JdbcExecutor(connections))
          .query(description)
          .bind(name, "Ada")
          .bind(petId, 7L)
          .fetchList();
      assertEquals(List.of(List.of(7L)), connections.bindings());
    }

    SelectDescription<Pet> noRemainingParameters =
        Sql.selectFrom(PET_TABLE).where(scalar.in(List.of()));
    DefaultSelectQuery<Pet> query =
        (DefaultSelectQuery<Pet>) operations().query(noRemainingParameters).bind(name, "Ada");
    assertSame(NoParameters.INSTANCE, query.compilation(QueryPagination.None.INSTANCE).argument());
    assertSame(NoParameters.INSTANCE, query.countCompilation().argument());
    assertThrows(
        QueryValidationException.class,
        () -> operations().query(noRemainingParameters).fetchList());
  }

  @Test
  void emptyScalarMembershipStillValidatesTheDiscardedOperandInItsOriginalScope() {
    OwnerTable innerOwner = OWNER_TABLE.as("scalar_owner");
    OwnerTable invisible = OWNER_TABLE.as("invisible_owner");
    QueryParameter<String> name = Sql.parameter(String.class, "name");
    Selectable<Long> invalid =
        Sql.scalar(Sql.select(invisible.id()).from(innerOwner).where(innerOwner.name().eq(name)));
    SelectDescription<Pet> description = Sql.selectFrom(PET_TABLE).where(invalid.in(List.of()));

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () -> operations().query(description).bind(name, "Ada").fetchList());

    assertEquals(SkisErrorCode.QUERY_COMPILED_SHAPE, failure.code());
    assertTrue(
        failure.getCause().getMessage().contains("not visible in the current or any ancestor"));
  }

  @Test
  void distinguishesIndependentScalarOrderingParametersAndRejectsActualDuplicates() {
    OwnerTable innerOwner = OWNER_TABLE.as("scalar_owner");
    QueryParameter<String> firstName = Sql.parameter(String.class, "name");
    QueryParameter<String> secondName = Sql.parameter(String.class, "name");
    Selectable<Long> first =
        Sql.scalar(
            Sql.select(innerOwner.id()).from(innerOwner).where(innerOwner.name().eq(firstName)));
    Selectable<Long> second =
        Sql.scalar(
            Sql.select(innerOwner.id()).from(innerOwner).where(innerOwner.name().eq(secondName)));
    Selectable<Long> sameReference =
        Sql.scalar(
            Sql.select(innerOwner.id()).from(innerOwner).where(innerOwner.name().eq(firstName)));
    SelectDescription<Pet> description =
        Sql.selectFrom(PET_TABLE).orderBy(first.asc(), second.asc());
    DefaultSelectQuery<Pet> query =
        (DefaultSelectQuery<Pet>)
            operations().query(description).bind(firstName, "Ada").bind(secondName, "Grace");

    QueryCompilation<Pet> compilation = query.compilation(QueryPagination.None.INSTANCE);

    assertEquals(List.of("Ada", "Grace"), (QueryArguments) compilation.argument());
    assertEquals(
        List.of(0, 1),
        compilation.plan().renderedSql().parameters().stream()
            .map(io.skis.sql.ast.ParameterSlot::ordinal)
            .toList());
    assertFalse(first.asc().equals(second.asc()));
    assertEquals(first.asc(), sameReference.asc());
    assertEquals(first.asc().hashCode(), sameReference.asc().hashCode());
    assertThrows(
        QueryValidationException.class,
        () -> Sql.selectFrom(PET_TABLE).orderBy(first.asc(), sameReference.asc()));
    assertThrows(
        QueryValidationException.class,
        () ->
            operations()
                .query(Sql.select(first).from(PET_TABLE).distinct().orderBy(second.asc()))
                .bind(firstName, "Ada")
                .bind(secondName, "Grace")
                .fetchList());
  }

  @Test
  void ordersDistinctScalarOutputsWithoutAllocatingUnusedOrderingParameters() {
    OwnerTable lookup = OWNER_TABLE.as("scalar_owner");
    QueryParameter<String> name = Sql.parameter(String.class, "name");
    QueryParameter<Long> petId = Sql.parameter(Long.class, "petId");
    Selectable<Long> scalar =
        Sql.scalar(
            Sql.select(lookup.id())
                .from(lookup)
                .where(lookup.id().eq(PET_TABLE.ownerId()).and(lookup.name().eq(name))));
    SingleColumnSelect<Long> description =
        Sql.select(scalar)
            .from(PET_TABLE)
            .where(PET_TABLE.id().eq(petId))
            .distinct()
            .orderBy(scalar.desc().nullsFirst());
    DefaultSelectQuery<Long> query =
        (DefaultSelectQuery<Long>)
            operations().query(description).bind(name, "Ada").bind(petId, 7L);

    QueryCompilation<Long> content = query.compilation(QueryPagination.None.INSTANCE);
    assertEquals(
        "SELECT DISTINCT (SELECT \"scalar_owner\".\"id\" FROM \"shelter\".\"owner\" AS "
            + "\"scalar_owner\" WHERE \"scalar_owner\".\"id\" = \"pet\".\"owner_id\" AND "
            + "\"scalar_owner\".\"owner_name\" = ?) FROM \"shelter\".\"pet\" WHERE "
            + "\"pet\".\"id\" = ? ORDER BY 1 DESC NULLS FIRST",
        content.plan().sql());
    assertEquals(List.of("Ada", 7L), (QueryArguments) content.argument());
    assertEquals(
        List.of(0, 1),
        content.plan().renderedSql().parameters().stream()
            .map(io.skis.sql.ast.ParameterSlot::ordinal)
            .toList());
    assertSame(content.plan(), query.compilation(QueryPagination.None.INSTANCE).plan());

    QueryCompilation<Long> page = query.compilation(new QueryPagination.Offset(2, 0));
    assertTrue(page.plan().sql().endsWith("ORDER BY 1 DESC NULLS FIRST LIMIT ? OFFSET ?"));
    assertEquals(List.of("Ada", 7L, 2, 0L), (QueryArguments) page.argument());
    QueryCompilation<Long> count = query.countCompilation();
    assertEquals(List.of("Ada", 7L), (QueryArguments) count.argument());
    assertEquals(
        List.of(0, 0, 1),
        count.plan().renderedSql().parameters().stream()
            .map(io.skis.sql.ast.ParameterSlot::ordinal)
            .toList());

    ScriptedQueryConnectionProvider connections =
        new ScriptedQueryConnectionProvider(List.of(List.of()));
    queryPlanCatalog()
        .bind(new JdbcExecutor(connections))
        .query(description)
        .bind(name, "Ada")
        .bind(petId, 7L)
        .fetchList();
    assertEquals(List.of(List.of("Ada", 7L)), connections.bindings());
  }

  @Test
  void validatesTheOriginalScalarOrderingOccurrenceBeforeSharingASelectedOutput() {
    OwnerTable lookup = OWNER_TABLE.as("scalar_owner");
    OwnerTable wrongInstance = OWNER_TABLE.as("scalar_owner");
    QueryParameter<String> name = Sql.parameter(String.class, "name");
    Selectable<Long> selected =
        Sql.scalar(Sql.select(lookup.id()).from(lookup).where(lookup.name().eq(name)));
    Selectable<Long> invalidOrder =
        Sql.scalar(Sql.select(wrongInstance.id()).from(lookup).where(lookup.name().eq(name)));
    SingleColumnSelect<Long> description =
        Sql.select(selected).from(PET_TABLE).distinct().orderBy(invalidOrder.asc());

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () -> operations().query(description).bind(name, "Ada").fetchList());
    assertEquals(SkisErrorCode.QUERY_COMPILED_SHAPE, failure.code());
    assertTrue(failure.getCause().getMessage().contains("ORDER BY[0]"));
    assertTrue(failure.getCause().getMessage().contains("not visible"));
  }

  @Test
  void rejectsScalarSliceOrderingBeforeAcquiringJdbcRegardlessOfPageSize() {
    OwnerTable innerOwner = OWNER_TABLE.as("scalar_owner");
    Selectable<Long> scalar =
        Sql.scalar(
            Sql.select(innerOwner.id())
                .from(innerOwner)
                .where(innerOwner.id().eq(PET_TABLE.ownerId())));
    SelectQuery<Pet> query =
        operations().selectFrom(PET_TABLE).orderBy(scalar.asc().nullsLast(), PET_TABLE.id().asc());

    for (int pageSize : List.of(1, 100)) {
      QueryValidationException failure =
          assertThrows(
              QueryValidationException.class,
              () -> query.fetchSlice(SliceRequest.offset(0, pageSize)));
      assertTrue(failure.getMessage().contains("pagination identity analysis"));
    }
    assertThrows(
        QueryValidationException.class, () -> query.fetchSlice(SliceRequest.keysetFirst(1)));

    QueryCompilation<Pet> pageContent =
        ((DefaultSelectQuery<Pet>) query).compilation(new QueryPagination.Offset(10, 0));
    assertTrue(pageContent.plan().sql().contains("ORDER BY (SELECT "));
    assertTrue(pageContent.plan().sql().endsWith("LIMIT ? OFFSET ?"));
  }

  @Test
  void distinguishesOuterNoRowFromPresentNullForScalarResultsWithoutExtraSql() {
    OwnerTable innerOwner = OWNER_TABLE.as("scalar_owner");
    Selectable<String> scalar =
        Sql.scalar(
            Sql.select(innerOwner.name())
                .from(innerOwner)
                .where(innerOwner.id().eq(PET_TABLE.ownerId())));
    SingleColumnSelect<@Nullable String> description = Sql.select(scalar).from(PET_TABLE);
    ScriptedQueryConnectionProvider connections =
        new ScriptedQueryConnectionProvider(
            List.of(List.of(Map.<Integer, Object>of()), List.of(), List.of(Map.of(1, "Ada"))));
    QueryOperations operations = queryPlanCatalog().bind(new JdbcExecutor(connections));

    SingleRow<@Nullable String> presentNull = operations.query(description).fetchOne();
    SingleRow<@Nullable String> noRow = operations.query(description).fetchOne();
    SingleRow<@Nullable String> presentValue = operations.query(description).fetchOne();

    assertTrue(presentNull.hasRow() && presentNull.isNull());
    assertTrue(!noRow.hasRow());
    assertEquals(SingleRow.present("Ada"), presentValue);
    assertEquals(3, connections.sql().size());
    assertTrue(connections.sql().stream().allMatch(sql -> sql.contains("(SELECT ")));
  }

  @Test
  void preservesDatabaseScalarCardinalityFailureAndSuppressedCloseOrder() {
    SQLException cardinality =
        new SQLException("scalar subquery returned multiple rows", "21000", 41);
    SQLException statementClose = new SQLException("statement close failed", "HY000", 42);
    SQLException release = new SQLException("release failed", "08006", 43);
    AtomicInteger releases = new AtomicInteger();
    ConnectionProvider provider =
        new ConnectionProvider() {
          @Override
          public Connection acquire(ExecutionContext context) {
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (ignored, method, arguments) -> {
                      if (method.getName().equals("prepareStatement")) {
                        return Proxy.newProxyInstance(
                            PreparedStatement.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (target, statementMethod, statementArguments) -> {
                              if (statementMethod.getName().equals("executeQuery")) {
                                throw cardinality;
                              }
                              if (statementMethod.getName().equals("close")) {
                                throw statementClose;
                              }
                              return defaultValue(statementMethod.getReturnType());
                            });
                      }
                      return defaultValue(method.getReturnType());
                    });
          }

          @Override
          public void release(Connection connection, ExecutionContext context) throws SQLException {
            releases.incrementAndGet();
            throw release;
          }
        };
    OwnerTable innerOwner = OWNER_TABLE.as("scalar_owner");
    Selectable<String> scalar = Sql.scalar(Sql.select(innerOwner.name()).from(innerOwner));
    QueryOperations operations = queryPlanCatalog().bind(new JdbcExecutor(provider));

    QueryExecutionException thrown =
        assertThrows(
            QueryExecutionException.class,
            () -> operations.query(Sql.select(scalar).from(PET_TABLE)).fetchList());

    assertSame(cardinality, thrown.getCause());
    assertEquals("21000", thrown.sqlState());
    assertEquals(Integer.valueOf(41), thrown.vendorCode());
    assertEquals(1, cardinality.getSuppressed().length);
    assertSame(statementClose, cardinality.getSuppressed()[0]);
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(release, thrown.getSuppressed()[0]);
    assertEquals(1, releases.get());
  }

  @Test
  void rejectsInvalidDistinctOrderingInsideMembershipAndExistsSubqueries() {
    SingleColumnSelect<Long> invalidOwnerIds =
        Sql.select(OWNER_TABLE.id()).from(OWNER_TABLE).distinct().orderBy(OWNER_TABLE.name().asc());
    SelectDescription<Pet> membershipDescription =
        Sql.selectFrom(PET_TABLE).where(PET_TABLE.ownerId().in(invalidOwnerIds));
    SelectDescription<Pet> existsDescription =
        Sql.selectFrom(PET_TABLE).where(Sql.exists(invalidOwnerIds));

    QueryValidationException membershipFailure =
        assertThrows(
            QueryValidationException.class,
            () ->
                ((DefaultSelectQuery<Pet>) operations().query(membershipDescription))
                    .compilation(QueryPagination.None.INSTANCE));
    QueryValidationException existsFailure =
        assertThrows(
            QueryValidationException.class,
            () ->
                ((DefaultSelectQuery<Pet>) operations().query(existsDescription))
                    .compilation(QueryPagination.None.INSTANCE));

    assertTrue(membershipFailure.getMessage().contains("is not part of the selected result"));
    assertEquals(membershipFailure.getMessage(), existsFailure.getMessage());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void keepsCollectionAndSubqueryMembershipNodesSeparateAndRejectsRawTypeMismatches() {
    SingleColumnSelect<Long> ownerIds = Sql.select(OWNER_TABLE.id()).from(OWNER_TABLE);
    ConditionCompilation collection = compileCondition(PET_TABLE.id().in(List.of(1L, 2L)));
    ConditionCompilation subquery = compileCondition(PET_TABLE.ownerId().in(ownerIds));

    assertInstanceOf(io.skis.sql.ast.InPredicate.class, collection.ast());
    assertInstanceOf(io.skis.sql.ast.InSubqueryPredicate.class, subquery.ast());

    SingleColumnSelect wrongType = Sql.select(OWNER_TABLE.name()).from(OWNER_TABLE);
    assertThrows(QueryValidationException.class, () -> ((Selectable) PET_TABLE.id()).in(wrongType));
  }

  @Test
  void relocatesOneDescriptionIntoIndependentInSubqueryOccurrences() {
    OwnerTable innerOwner = OWNER_TABLE.as("membership_owner");
    QueryParameter<String> name = Sql.parameter(String.class, "name");
    SingleColumnSelect<Long> ownerIds =
        Sql.select(innerOwner.id()).from(innerOwner).where(innerOwner.name().eq(name));
    SingleColumnSelect<Long> description =
        Sql.select(PET_TABLE.id())
            .from(PET_TABLE)
            .where(PET_TABLE.ownerId().in(ownerIds).or(PET_TABLE.ownerId().notIn(ownerIds)));
    DefaultSelectQuery<Long> query =
        (DefaultSelectQuery<Long>) operations().query(description).bind(name, "Ada");

    QueryCompilation<Long> compilation = query.compilation(QueryPagination.None.INSTANCE);
    QueryBlockAnalysis analysis =
        SemanticValidator.analyzeComplete((SelectStatement) compilation.ast());

    assertEquals(2, analysis.nestedBlocks().size());
    assertTrue(
        analysis.nestedBlocks().stream()
            .allMatch(nested -> nested.kind() == QueryBlockAnalysis.NestedQueryKind.IN_SUBQUERY));
    assertEquals(List.of("Ada", "Ada"), (QueryArguments) compilation.argument());
    assertEquals(
        List.of(0, 1),
        compilation.plan().renderedSql().parameters().stream()
            .map(io.skis.sql.ast.ParameterSlot::ordinal)
            .toList());
    assertTrue(compilation.plan().sql().contains(" IN (SELECT "));
    assertTrue(compilation.plan().sql().contains(" NOT IN (SELECT "));
  }

  @Test
  void relocatesOneDescriptionIntoIndependentExistsOccurrences() {
    PetTable innerPet = PET_TABLE.as("reused_pet");
    QueryParameter<String> name = Sql.parameter(String.class, "name");
    SingleColumnSelect<Long> matchingPets =
        Sql.select(innerPet.id()).from(innerPet).where(innerPet.name().eq(name));
    SelectDescription<Owner> description =
        Sql.selectFrom(OWNER_TABLE).where(Sql.exists(matchingPets).or(Sql.notExists(matchingPets)));
    DefaultSelectQuery<Owner> query =
        (DefaultSelectQuery<Owner>) operations().query(description).bind(name, "Mimi");

    QueryCompilation<Owner> compilation = query.compilation(QueryPagination.None.INSTANCE);
    QueryBlockAnalysis analysis =
        SemanticValidator.analyzeComplete((SelectStatement) compilation.ast());

    assertEquals(2, analysis.nestedBlocks().size());
    assertEquals("$/WHERE[0]#0", analysis.nestedBlocks().get(0).analysis().path().toString());
    assertEquals("$/WHERE[0]#1", analysis.nestedBlocks().get(1).analysis().path().toString());
    assertEquals(List.of("Mimi", "Mimi"), (QueryArguments) compilation.argument());
    assertEquals(
        List.of(0, 1),
        compilation.plan().renderedSql().parameters().stream()
            .map(io.skis.sql.ast.ParameterSlot::ordinal)
            .toList());
    assertTrue(compilation.plan().sql().contains("EXISTS ("));
    assertTrue(compilation.plan().sql().contains("NOT EXISTS ("));
    assertFalse(compilation.plan().sql().contains(" LIMIT "));
  }

  @Test
  void resolvesExistsAgainstItsExactJoinOnScopeAndRejectsFutureSources() {
    PetTable joinedPet = PET_TABLE.as("joined_pet");
    PetTable witness = PET_TABLE.as("witness_pet");
    SingleColumnSelect<Long> onWitness =
        Sql.select(witness.id())
            .from(witness)
            .where(witness.ownerId().eq(OWNER_TABLE.id()).and(witness.id().eq(joinedPet.id())));
    SelectDescription<Owner> valid =
        Sql.selectFrom(OWNER_TABLE).join(joinedPet).on(Sql.exists(onWitness));

    String sql =
        ((DefaultSelectQuery<Owner>) operations().query(valid))
            .compilation(QueryPagination.None.INSTANCE)
            .plan()
            .sql();
    assertTrue(sql.contains("ON EXISTS ("));
    assertTrue(sql.contains("\"witness_pet\".\"owner_id\" = \"owner\".\"id\""));
    assertTrue(sql.contains("\"witness_pet\".\"id\" = \"joined_pet\".\"id\""));

    OwnerTable futureOwner = OWNER_TABLE.as("future_owner");
    PetTable firstPet = PET_TABLE.as("first_pet");
    PetTable invalidWitness = PET_TABLE.as("invalid_witness");
    SingleColumnSelect<Long> futureReference =
        Sql.select(invalidWitness.id())
            .from(invalidWitness)
            .where(invalidWitness.ownerId().eq(futureOwner.id()));
    SelectDescription<Owner> invalid =
        Sql.selectFrom(OWNER_TABLE)
            .join(firstPet)
            .on(Sql.exists(futureReference))
            .join(futureOwner)
            .on(firstPet.ownerId().eq(futureOwner.id()));

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () ->
                ((DefaultSelectQuery<Owner>) operations().query(invalid))
                    .compilation(QueryPagination.None.INSTANCE));
    assertEquals(SkisErrorCode.QUERY_COMPILED_SHAPE, failure.code());
    assertTrue(failure.getCause().getMessage().contains("JOIN ON"));
    assertTrue(failure.getCause().getMessage().contains("before it is visible"));
  }

  @Test
  void rejectsAStandaloneCorrelatedDescriptionButAllowsItWhenEmbedded() {
    PetTable innerPet = PET_TABLE.as("correlated_pet");
    SingleColumnSelect<Long> correlated =
        Sql.select(innerPet.id()).from(innerPet).where(innerPet.ownerId().eq(OWNER_TABLE.id()));

    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () ->
                ((DefaultSelectQuery<Long>) operations().query(correlated))
                    .compilation(QueryPagination.None.INSTANCE));
    assertEquals(SkisErrorCode.QUERY_COMPILED_SHAPE, failure.code());
    assertTrue(failure.getCause().getMessage().contains("unresolved outer reference"));

    SelectDescription<Owner> outer = Sql.selectFrom(OWNER_TABLE).where(Sql.exists(correlated));
    ((DefaultSelectQuery<Owner>) operations().query(outer))
        .compilation(QueryPagination.None.INSTANCE);
  }

  private static void assertJoinSql(SelectQuery<? extends @Nullable Pet> query, String keyword) {
    String sql =
        ((DefaultSelectQuery<?>) query).compilation(QueryPagination.None.INSTANCE).plan().sql();
    assertJoinSql(sql, keyword);
  }

  private static void assertJoinSql(String sql, String keyword) {
    assertTrue(sql.contains(keyword));
    assertEquals(keyword.contains("CROSS"), !sql.contains(" ON "));
  }

  private static ConditionCompilation compileCondition(QueryCondition condition) {
    StatementParameterLayout layout = new StatementParameterLayout();
    QueryParameterBindings bindings = new QueryParameterBindings();
    QueryConditionCompiler compiler = new QueryConditionCompiler(layout, bindings);
    SqlPredicate ast = QueryConditions.compile(condition, compiler);
    return new ConditionCompilation(
        ast,
        layout.parameterSources(),
        bindings.parameters().valuesFor(layout.parameterReferences()));
  }

  private static QueryOperations operations() {
    return queryPlanCatalog()
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

  @Test
  void executableAggregateDslCapturesEveryExpressionBoundaryWithoutExecutingSql() {
    QueryOperations operations = operations();
    NonNullSelectable<Long> value = Sql.value(17L);
    @SuppressWarnings("unchecked")
    QueryParameter<Long> reference = (QueryParameter<Long>)
        SelectableSupport.expressionIdentity(value).parameters().getFirst();
    SelectQuery<Pet> base = operations.selectFrom(PET_TABLE);
    List<SelectQuery<?>> captured = List.of(
        operations.select(value).from(PET_TABLE),
        base.groupBy(value),
        base.having(Sql.countAll().ge(value)),
        base.where(value.eq(PET_TABLE.ownerId())),
        base.orderBy(value.asc()),
        base.join(OWNER_TABLE).on(value.eq(OWNER_TABLE.id())));
    for (SelectQuery<?> query : captured) {
      assertEquals(SkisErrorCode.QUERY_PARAMETER_DECLARATION,
          assertThrows(QueryValidationException.class, () -> query.bind(reference, 18L)).code());
    }
    // Reusing the same captured fragment across boundaries must retain its one binding identity.
    SelectQuery<Long> shared = operations.select(value).from(PET_TABLE)
        .groupBy(value).where(value.isNotNull()).having(value.isNotNull()).orderBy(value.asc());
    assertEquals(SkisErrorCode.QUERY_PARAMETER_DECLARATION,
        assertThrows(QueryValidationException.class, () -> shared.bind(reference, 18L)).code());
    assertTrue(((DefaultSelectQuery<Pet>) base).compilation(QueryPagination.None.INSTANCE)
        .plan().sql().startsWith("SELECT"));
  }

  @Test
  void executableGroupingConstructionPreservesBranchesAndItsOwnDiagnosticCodes() {
    SelectQuery<Pet> base = operations().selectFrom(PET_TABLE);
    assertEquals(SkisErrorCode.QUERY_GROUP_BY_CONTRACT,
        assertThrows(QueryValidationException.class, base::groupBy).code());
    assertEquals(SkisErrorCode.QUERY_GROUP_BY_CONTRACT,
        assertThrows(QueryValidationException.class,
            () -> base.groupBy(PET_TABLE.id()).groupBy(PET_TABLE.name())).code());
    assertEquals(SkisErrorCode.QUERY_HAVING_CONTRACT,
        assertThrows(QueryValidationException.class, () -> base.having(null)).code());
    assertEquals(SkisErrorCode.QUERY_HAVING_CONTRACT,
        assertThrows(QueryValidationException.class, () -> base.andHaving(PET_TABLE.id().isNotNull())).code());
    assertEquals(SkisErrorCode.QUERY_HAVING_CONTRACT,
        assertThrows(QueryValidationException.class, () -> base.orHaving(PET_TABLE.id().isNotNull())).code());
    QueryCondition condition = PET_TABLE.id().isNotNull();
    SelectQuery<Pet> branch = base.having(condition).where(condition);
    assertEquals(SkisErrorCode.QUERY_HAVING_CONTRACT,
        assertThrows(QueryValidationException.class, () -> branch.having(condition)).code());
    assertEquals(SkisErrorCode.QUERY_WHERE_CONTRACT,
        assertThrows(QueryValidationException.class, () -> branch.where(condition)).code());
    assertNotSame(base.groupBy(PET_TABLE.id()), base.groupBy(PET_TABLE.name()));
    ((DefaultSelectQuery<Pet>) base).compilation(QueryPagination.None.INSTANCE);
  }

  @Test
  void orderingOwnershipIsReleasedWhenLaterClausesCaptureTheSameValue() {
    SelectQuery<Pet> base = operations().selectFrom(PET_TABLE);
    NonNullSelectable<Long> shared = Sql.value(13L);
    @SuppressWarnings("unchecked")
    QueryParameter<Long> parameter = (QueryParameter<Long>)
        SelectableSupport.expressionIdentity(shared).parameters().getFirst();
    SelectQuery<Pet> ordered = base.orderBy(shared.asc());
    List<SelectQuery<Pet>> retained = List.of(
        ordered.where(shared.isNotNull()),
        ordered.groupBy(shared),
        ordered.having(shared.isNotNull()),
        ordered.join(OWNER_TABLE).on(shared.eq(OWNER_TABLE.id())));
    for (SelectQuery<Pet> query : retained) {
      SelectQuery<Pet> replaced = query.orderBy(PET_TABLE.id().asc());
      assertEquals(SkisErrorCode.QUERY_PARAMETER_DECLARATION,
          assertThrows(QueryValidationException.class, () -> replaced.bind(parameter, 14L)).code());
    }
    // Descendants cannot change the original ordering's exclusive ownership.
    ((DefaultSelectQuery<Pet>) ordered.orderBy(PET_TABLE.id().asc()))
        .compilation(QueryPagination.None.INSTANCE);
    QueryParameter<Long> explicit = Sql.parameter(Long.class, "id");
    SelectQuery<Pet> bound = ordered.where(PET_TABLE.id().eq(explicit)).bind(explicit, 7L);
    QueryCompilation<Pet> compiled = ((DefaultSelectQuery<Pet>) bound
        .orderBy(PET_TABLE.id().asc())).compilation(QueryPagination.None.INSTANCE);
    assertEquals(List.of(7L), compiled.argument());
    assertEquals(SkisErrorCode.QUERY_PARAMETER_DECLARATION,
        assertThrows(QueryValidationException.class, () -> bound.bind(explicit, 8L)).code());
    SelectQuery<Pet> replacedTwice = ordered
        .orderBy(Sql.value(14L).asc(), Sql.value(15L).desc())
        .orderBy(PET_TABLE.id().asc());
    ((DefaultSelectQuery<Pet>) replacedTwice).compilation(QueryPagination.None.INSTANCE);
  }

  @Test
  void replacingOrderingReleasesOnlyValuesOwnedByTheOldOrdering() {
    SelectQuery<Pet> base = operations().selectFrom(PET_TABLE);
    SelectQuery<Pet> ordered = base.orderBy(Sql.value(11L).asc());
    SelectQuery<Pet> replacement = ordered.orderBy(PET_TABLE.id().asc());
    ((DefaultSelectQuery<Pet>) replacement).compilation(QueryPagination.None.INSTANCE);
    SelectQuery<Pet> withWhere = ordered.where(PET_TABLE.id().eq(7L));
    QueryCompilation<Pet> compiled = ((DefaultSelectQuery<Pet>) withWhere
        .orderBy(PET_TABLE.id().asc())).compilation(QueryPagination.None.INSTANCE);
    assertTrue(compiled.plan().sql().contains("WHERE"));
    SelectQuery<Pet> twice = ordered.orderBy(Sql.value(12L).desc()).distinct()
        .orderBy(PET_TABLE.id().asc());
    ((DefaultSelectQuery<Pet>) twice).compilation(QueryPagination.None.INSTANCE);
    NonNullSelectable<Long> shared = Sql.value(13L);
    @SuppressWarnings("unchecked")
    QueryParameter<Long> parameter = (QueryParameter<Long>)
        SelectableSupport.expressionIdentity(shared).parameters().getFirst();
    SelectQuery<Long> retained = operations().select(shared).from(PET_TABLE)
        .orderBy(shared.asc()).orderBy(PET_TABLE.id().asc());
    assertEquals(SkisErrorCode.QUERY_PARAMETER_DECLARATION,
        assertThrows(QueryValidationException.class, () -> retained.bind(parameter, 14L)).code());
    ((DefaultSelectQuery<Pet>) base).compilation(QueryPagination.None.INSTANCE);
  }

  private static QueryPlanCatalog queryPlanCatalog() {
    EntityRuntimeRegistry registry =
        EntityRuntimeRegistry.of(List.of(petModel(), ownerModel(), readOnlyViewModel()));
    return QueryRuntime.compile(registry, TestDialect.INSTANCE);
  }

  private static <E, R> QueryCompilation<R> compileSelection(
      QueryPlanCatalog catalog,
      QueryTable<E> root,
      SelectedResult<R> selected,
      List<QueryJoin> joins) {
    CompiledQueryStructure structure = QueryTestSupport.compile(selected, root, joins, null);
    return catalog
        .compiler()
        .compileSelection(
            selected,
            structure,
            List.of(),
            false,
            QueryPagination.None.INSTANCE,
            List.of(),
            QueryTestSupport.arguments(structure),
            null,
            StatementPurpose.PRIMARY);
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

  private static EntityRuntimeModel<ReadOnlyView> readOnlyViewModel() {
    return new EntityRuntimeModel<>(
        READ_ONLY_VIEW,
        layout -> (resultSet, context) -> new ReadOnlyView(1L),
        List.of(new PropertyRuntime<>(VIEW_VALUE, JdbcCodecs.LONG)));
  }

  private static final class ScriptedQueryConnectionProvider implements ConnectionProvider {

    private final List<List<Map<Integer, Object>>> resultRows;
    private final List<String> sql = new ArrayList<>();
    private final List<List<Object>> bindings = new ArrayList<>();
    private int nextResult;

    private ScriptedQueryConnectionProvider(List<List<Map<Integer, Object>>> resultRows) {
      this.resultRows = List.copyOf(resultRows);
    }

    @Override
    public Connection acquire(ExecutionContext context) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (ignored, method, arguments) -> {
                if (method.getName().equals("prepareStatement")) {
                  sql.add((String) arguments[0]);
                  return preparedStatement();
                }
                return defaultValue(method.getReturnType());
              });
    }

    @Override
    public void release(Connection connection, ExecutionContext context) {}

    private PreparedStatement preparedStatement() {
      List<Object> values = new ArrayList<>();
      return (PreparedStatement)
          Proxy.newProxyInstance(
              PreparedStatement.class.getClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              (ignored, method, arguments) -> {
                if ((method.getName().equals("setString") || method.getName().equals("setLong"))
                    && arguments[0] instanceof Integer index) {
                  while (values.size() < index) {
                    values.add(null);
                  }
                  values.set(index - 1, arguments[1]);
                  return null;
                }
                if (method.getName().equals("executeQuery")) {
                  if (nextResult >= resultRows.size()) {
                    throw new AssertionError("no scripted query result remains");
                  }
                  bindings.add(List.copyOf(values));
                  return rowsResultSet(resultRows.get(nextResult++));
                }
                return defaultValue(method.getReturnType());
              });
    }

    private List<String> sql() {
      return List.copyOf(sql);
    }

    private List<List<Object>> bindings() {
      return List.copyOf(bindings);
    }
  }

  private static ResultSet rowsResultSet(List<Map<Integer, Object>> rows) {
    int[] rowIndex = {-1};
    int[] lastColumnIndex = new int[1];
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (ignored, method, arguments) -> {
              if (method.getName().equals("next")) {
                rowIndex[0]++;
                return rowIndex[0] < rows.size();
              }
              if (method.getName().equals("wasNull")) {
                return rows.get(rowIndex[0]).get(lastColumnIndex[0]) == null;
              }
              if (method.getName().equals("getLong")) {
                int index = (Integer) arguments[0];
                lastColumnIndex[0] = index;
                Object value = rows.get(rowIndex[0]).get(index);
                return value == null ? 0L : ((Number) value).longValue();
              }
              if (method.getName().equals("getString")) {
                int index = (Integer) arguments[0];
                lastColumnIndex[0] = index;
                return (String) rows.get(rowIndex[0]).get(index);
              }
              return defaultValue(method.getReturnType());
            });
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
              if (method.getName().equals("getLong")) {
                int index = (Integer) arguments[0];
                lastIndex[0] = index;
                Object value = values.get(index);
                return value == null ? 0L : ((Number) value).longValue();
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

  private record Pet(Long id, Long ownerId, String name) {}

  private record Owner(Long id, String name) {}

  private record ReadOnlyView(Long value) {}

  private record ConditionCompilation(
      SqlPredicate ast, List<Selectable<?>> parameterSources, List<@Nullable Object> arguments) {}

  private static final class PetTable extends QueryTable<Pet> {

    private final NonNullSelectable<Long> id = nonNullQueryColumn(PET_ID);
    private final NonNullSelectable<Long> ownerId = nonNullQueryColumn(PET_OWNER_ID);
    private final NonNullSelectable<String> name = nonNullQueryColumn(PET_NAME);

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

    private NonNullSelectable<String> name() {
      return name;
    }

    @Override
    public PetTable as(String alias) {
      return new PetTable(alias);
    }
  }

  private static final class OwnerTable extends QueryTable<Owner> {

    private final NonNullSelectable<Long> id = nonNullQueryColumn(OWNER_ID);
    private final Selectable<String> name = nullableQueryColumn(OWNER_NAME);

    private OwnerTable() {
      super(OWNER);
    }

    private OwnerTable(String alias) {
      super(OWNER, alias);
    }

    private NonNullSelectable<Long> id() {
      return id;
    }

    private Selectable<String> name() {
      return name;
    }

    @Override
    public OwnerTable as(String alias) {
      return new OwnerTable(alias);
    }
  }

  private static final class ReadOnlyViewTable extends QueryTable<ReadOnlyView> {

    private ReadOnlyViewTable() {
      super(READ_ONLY_VIEW);
    }

    private ReadOnlyViewTable(String alias) {
      super(READ_ONLY_VIEW, alias);
    }

    @Override
    public ReadOnlyViewTable as(String alias) {
      return new ReadOnlyViewTable(alias);
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
            DialectFeature.PARAMETERIZED_LIMIT,
            DialectFeature.PARAMETERIZED_OFFSET,
            DialectFeature.NULLS_FIRST_LAST,
            DialectFeature.COUNT_AGGREGATE,
            DialectFeature.COUNT_DISTINCT,
            DialectFeature.EXISTS_SUBQUERY,
            DialectFeature.IN_SUBQUERY,
            DialectFeature.SCALAR_SUBQUERY,
            DialectFeature.CORRELATED_SUBQUERY);
    private final SqlRenderer renderer = new StandardSqlRenderer(this);

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
