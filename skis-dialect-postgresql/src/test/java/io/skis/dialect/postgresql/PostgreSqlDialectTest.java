package io.skis.dialect.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.DialectFeature;
import io.skis.dialect.RenderedSql;
import io.skis.dialect.SqlExceptionCategory;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.ArithmeticOperator;
import io.skis.sql.ast.BetweenPredicate;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.CaseWhen;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.CoalesceExpression;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ConcatExpression;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.KeysetSeek;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NotPredicate;
import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.OffsetLimit;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.OrderDirection;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.TableExpression;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.skis.PetMeta;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostgreSqlDialectTest {

  @Test
  void exposesTheInitialPostgreSqlContract() {
    PostgreSqlDialect dialect = PostgreSqlDialect.INSTANCE;

    assertEquals("postgresql", dialect.id());
    assertSame(PostgreSqlRenderer.INSTANCE, dialect.renderer());
    assertSame(PostgreSqlExceptionClassifier.INSTANCE, dialect.exceptionClassifier());
    assertTrue(dialect.capabilities().supports(DialectFeature.SCHEMA_QUALIFIED_TABLES));
    assertTrue(dialect.capabilities().supports(DialectFeature.PARAMETERIZED_LIMIT));
    assertTrue(dialect.capabilities().supports(DialectFeature.PARAMETERIZED_OFFSET));
    assertTrue(dialect.capabilities().supports(DialectFeature.NULLS_FIRST_LAST));
    assertTrue(dialect.capabilities().supports(DialectFeature.COUNT_DISTINCT));
    assertFalse(dialect.capabilities().supports(DialectFeature.CATALOG_QUALIFIED_TABLES));
    assertEquals("\"select\"", dialect.identifierRules().quote("select"));
  }

  @Test
  void rendersNativeNullOrderingPageKeysetAndCountGoldenSql() {
    PetAstTable pet = PetAstTable.PET;
    ParameterSlot<Integer> limit = new ParameterSlot<>(0, Integer.class, false);
    ParameterSlot<Long> offset = new ParameterSlot<>(1, Long.class, false);
    RenderedSql renderedPage =
        PostgreSqlDialect.INSTANCE
            .renderer()
            .render(
                new SelectStatement(
                    false,
                    List.of(pet.id(), pet.name()),
                    List.of(),
                    pet,
                    null,
                    List.of(
                        new OrderByItem(pet.name(), OrderDirection.DESC, NullOrder.LAST),
                        new OrderByItem(
                            pet.id(), OrderDirection.DESC, NullOrder.DIALECT_DEFAULT)),
                    new OffsetLimit(limit, offset)));
    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" "
            + "ORDER BY \"pet\".\"pet_name\" DESC NULLS LAST, \"pet\".\"id\" DESC "
            + "LIMIT ? OFFSET ?",
        renderedPage.sql());
    assertEquals(List.of(limit, offset), renderedPage.parameters());

    ParameterSlot<Long> anchor = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<Integer> keysetLimit = new ParameterSlot<>(1, Integer.class, false);
    RenderedSql renderedKeyset =
        PostgreSqlDialect.INSTANCE
            .renderer()
            .render(
                new SelectStatement(
                    false,
                    List.of(pet.id()),
                    List.of(),
                    pet,
                    null,
                    List.of(
                        new OrderByItem(
                            pet.id(), OrderDirection.ASC, NullOrder.DIALECT_DEFAULT)),
                    new KeysetSeek(pet.id().gt(anchor), keysetLimit)));
    assertEquals(
        "SELECT \"pet\".\"id\" FROM \"shelter\".\"pet\" "
            + "WHERE \"pet\".\"id\" > ? ORDER BY \"pet\".\"id\" ASC LIMIT ?",
        renderedKeyset.sql());
    assertEquals(List.of(anchor, keysetLimit), renderedKeyset.parameters());

    RenderedSql renderedCount =
        PostgreSqlDialect.INSTANCE.renderer().render(new CountAst(pet, null, pet.name()));
    assertEquals(
        "SELECT COUNT(DISTINCT \"pet\".\"pet_name\") FROM \"shelter\".\"pet\"",
        renderedCount.sql());

    RenderedSql renderedNullableCount =
        PostgreSqlDialect.INSTANCE
            .renderer()
            .render(
                new CountAst(
                    pet,
                    null,
                    new CaseExpression<>(
                        List.of(new CaseWhen<>(pet.id().isNotNull(), pet.name())))));
    assertEquals(
        "SELECT COUNT(DISTINCT CASE WHEN \"pet\".\"id\" IS NOT NULL "
            + "THEN \"pet\".\"pet_name\" END) + CASE WHEN COUNT(*) > "
            + "COUNT(CASE WHEN \"pet\".\"id\" IS NOT NULL THEN \"pet\".\"pet_name\" END) "
            + "THEN 1 ELSE 0 END "
            + "FROM \"shelter\".\"pet\"",
        renderedNullableCount.sql());
  }

  @Test
  void groupsAnExistingOrPredicateBeforeAppendingTheKeysetPredicate() {
    PetAstTable pet = PetAstTable.PET;
    ParameterSlot<Long> lower = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<Long> upper = new ParameterSlot<>(1, Long.class, false);
    ParameterSlot<Long> anchor = new ParameterSlot<>(2, Long.class, false);
    ParameterSlot<Integer> limit = new ParameterSlot<>(3, Integer.class, false);

    RenderedSql rendered =
        PostgreSqlDialect.INSTANCE
            .renderer()
            .render(
                new SelectStatement(
                    false,
                    List.of(pet.id()),
                    List.of(),
                    pet,
                    LogicalPredicate.or(List.of(pet.id().lt(lower), pet.id().gt(upper))),
                    List.of(
                        new OrderByItem(
                            pet.id(), OrderDirection.ASC, NullOrder.DIALECT_DEFAULT)),
                    new KeysetSeek(pet.id().gt(anchor), limit)));

    assertEquals(
        "SELECT \"pet\".\"id\" FROM \"shelter\".\"pet\" WHERE "
            + "(\"pet\".\"id\" < ? OR \"pet\".\"id\" > ?) "
            + "AND (\"pet\".\"id\" > ?) ORDER BY \"pet\".\"id\" ASC LIMIT ?",
        rendered.sql());
    assertEquals(List.of(lower, upper, anchor, limit), rendered.parameters());
  }

  @Test
  void classifiesPostgreSqlStatesWithoutInspectingMessages() {
    assertEquals(
        SqlExceptionCategory.DUPLICATE_KEY,
        classify(new SQLException("sensitive detail", "23505", 0)));
    assertEquals(
        SqlExceptionCategory.FOREIGN_KEY_VIOLATION,
        classify(new SQLException("sensitive detail", "23503", 0)));
    assertEquals(
        SqlExceptionCategory.CONSTRAINT_VIOLATION,
        classify(new SQLException("sensitive detail", "23514", 0)));
    assertEquals(
        SqlExceptionCategory.QUERY_CANCELED,
        classify(new SQLException("sensitive detail", "57014", 0)));
    assertEquals(
        SqlExceptionCategory.LOCK_NOT_AVAILABLE,
        classify(new SQLException("sensitive detail", "55P03", 0)));
    assertEquals(
        SqlExceptionCategory.DEADLOCK,
        classify(new SQLException("sensitive detail", "40P01", 0)));
    assertEquals(
        SqlExceptionCategory.SERIALIZATION_FAILURE,
        classify(new SQLException("sensitive detail", "40001", 0)));
    assertEquals(
        SqlExceptionCategory.CONNECTION_FAILURE,
        classify(new SQLException("sensitive detail", "08006", 0)));
    for (String state : List.of("57P01", "57P02", "57P03", "57P04", "57P05")) {
      assertEquals(
          SqlExceptionCategory.CONNECTION_FAILURE,
          classify(new SQLException("sensitive detail", state, 0)),
          state);
    }
    assertEquals(
        SqlExceptionCategory.UNCATEGORIZED,
        classify(new SQLException("sensitive detail", "42000", 0)));
  }

  @Test
  void preciseChainedStateWinsOverAnOuterGenericStateClass() {
    SQLException root = new SQLException("wrapper", "23000", 0);
    root.setNextException(new SQLException("duplicate", "23505", 0));

    assertEquals(SqlExceptionCategory.DUPLICATE_KEY, classify(root));
  }

  @Test
  void rendersSingleTableSelectGoldenSql() {
    PetAstTable pet = PetAstTable.PET;
    ParameterSlot<Long> id = new ParameterSlot<>(0, Long.class, false);
    SelectStatement statement =
        new SelectStatement(List.of(pet.id(), pet.name()), pet, pet.id().eq(id));

    RenderedSql rendered = PostgreSqlDialect.INSTANCE.renderer().render(statement);

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" WHERE \"pet\".\"id\" = ?",
        rendered.sql());
    assertEquals(List.of(id), rendered.parameters());
  }

  @Test
  void rendersComplexPredicateGoldenSql() {
    PetAstTable pet = PetAstTable.PET;
    ParameterSlot<Long> minimum = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<Long> maximum = new ParameterSlot<>(1, Long.class, false);
    ParameterSlot<String> firstPattern = new ParameterSlot<>(2, String.class, false);
    ParameterSlot<String> secondPattern = new ParameterSlot<>(3, String.class, false);
    ParameterSlot<Long> first = new ParameterSlot<>(4, Long.class, false);
    ParameterSlot<Long> second = new ParameterSlot<>(5, Long.class, false);
    ParameterSlot<Long> excludedMaximum = new ParameterSlot<>(6, Long.class, false);

    RenderedSql rendered =
        PostgreSqlDialect.INSTANCE
            .renderer()
            .render(
                new SelectStatement(
                    List.of(pet.id()),
                    pet,
                    LogicalPredicate.and(
                        List.of(
                            pet.name().isNotNull(),
                            new BetweenPredicate<>(pet.id(), minimum, maximum),
                            LogicalPredicate.or(
                                List.of(
                                    new LikePredicate(pet.name(), firstPattern),
                                    new LikePredicate(pet.name(), secondPattern))),
                            new InPredicate<>(pet.id(), List.of(first, second), false),
                            new NotPredicate(pet.id().le(excludedMaximum))))));

    assertEquals(
        "SELECT \"pet\".\"id\" FROM \"shelter\".\"pet\" WHERE "
            + "\"pet\".\"pet_name\" IS NOT NULL AND \"pet\".\"id\" BETWEEN ? AND ? "
            + "AND (\"pet\".\"pet_name\" LIKE ? OR \"pet\".\"pet_name\" LIKE ?) "
            + "AND \"pet\".\"id\" IN (?, ?) "
            + "AND NOT (\"pet\".\"id\" <= ?)",
        rendered.sql());
    assertEquals(
        List.of(minimum, maximum, firstPattern, secondPattern, first, second, excludedMaximum),
        rendered.parameters());
  }

  @Test
  void rendersPortableStandardExpressionGoldenSql() {
    PetAstTable pet = PetAstTable.PET;
    ParameterSlot<Long> addend = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<String> suffix = new ParameterSlot<>(1, String.class, false);
    ParameterSlot<Long> threshold = new ParameterSlot<>(2, Long.class, false);
    ParameterSlot<String> otherwise = new ParameterSlot<>(3, String.class, false);
    ParameterSlot<String> fallback = new ParameterSlot<>(4, String.class, false);

    RenderedSql rendered =
        PostgreSqlDialect.INSTANCE
            .renderer()
            .render(
                new SelectStatement(
                    List.of(
                        new ArithmeticExpression<>(pet.id(), ArithmeticOperator.ADD, addend),
                        new ConcatExpression(List.of(pet.name(), suffix)),
                        new CaseExpression<>(
                            List.of(new CaseWhen<>(pet.id().gt(threshold), pet.name())),
                            otherwise),
                        new CastExpression<>(pet.id(), String.class),
                        new CoalesceExpression<>(List.of(pet.name(), fallback))),
                    pet));

    assertEquals(
        "SELECT (\"pet\".\"id\" + ?), (\"pet\".\"pet_name\" || ?), "
            + "CASE WHEN \"pet\".\"id\" > ? THEN \"pet\".\"pet_name\" ELSE ? END, "
            + "CAST(\"pet\".\"id\" AS VARCHAR), COALESCE(\"pet\".\"pet_name\", ?) "
            + "FROM \"shelter\".\"pet\"",
        rendered.sql());
    assertEquals(List.of(addend, suffix, threshold, otherwise, fallback), rendered.parameters());
  }

  private static SqlExceptionCategory classify(SQLException exception) {
    return PostgreSqlDialect.INSTANCE.exceptionClassifier().classify(exception);
  }

  @Test
  void rendersAliasesWithoutUsingRawIdentifierText() {
    PetAstTable pet = PetAstTable.PET.as("p");

    RenderedSql rendered =
        PostgreSqlDialect.INSTANCE.renderer().render(new SelectStatement(List.of(pet.name()), pet));

    assertEquals("SELECT \"p\".\"pet_name\" FROM \"shelter\".\"pet\" AS \"p\"", rendered.sql());
  }

  @Test
  void rendersVersionCheckedUpdateGoldenSql() {
    PetAstTable pet = PetAstTable.PET;
    ParameterSlot<String> name = new ParameterSlot<>(0, String.class, false);
    ParameterSlot<Long> id = new ParameterSlot<>(1, Long.class, false);
    ParameterSlot<Long> version = new ParameterSlot<>(2, Long.class, false);

    RenderedSql rendered =
        PostgreSqlDialect.INSTANCE
            .renderer()
            .render(
                new UpdateStatement(
                    pet,
                    List.of(
                        new UpdateAssignment<>(pet.name(), name),
                        new UpdateAssignment<>(
                            pet.version(), new IncrementExpression<>(pet.version()))),
                    LogicalPredicate.and(
                        List.of(pet.id().eq(id), pet.version().eq(version)))));

    assertEquals(
        "UPDATE \"shelter\".\"pet\" SET \"pet_name\" = ?, \"version\" = \"version\" + 1 WHERE \"id\" = ? AND \"version\" = ?",
        rendered.sql());
    assertEquals(List.of(name, id, version), rendered.parameters());
  }

  private static final class PetAstTable extends TableExpression<Pet> {

    private static final PetAstTable PET = new PetAstTable();

    private final ColumnExpression<Pet, Long> id = column(PetMeta.ID);
    private final ColumnExpression<Pet, String> name = column(PetMeta.NAME);
    private final ColumnExpression<Pet, Long> version = column(PetMeta.VERSION_PROPERTY);

    private PetAstTable() {
      super(PetMeta.ENTITY);
    }

    private PetAstTable(Identifier alias) {
      super(PetMeta.ENTITY, alias);
    }

    private ColumnExpression<Pet, Long> id() {
      return id;
    }

    private ColumnExpression<Pet, String> name() {
      return name;
    }

    private ColumnExpression<Pet, Long> version() {
      return version;
    }

    @Override
    public PetAstTable as(String alias) {
      return new PetAstTable(Identifier.of(alias));
    }

    @Override
    public PetAstTable as(Identifier alias) {
      return new PetAstTable(alias);
    }
  }
}
