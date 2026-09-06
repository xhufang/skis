package io.skis.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.TableExpression;
import java.util.List;
import org.junit.jupiter.api.Test;

class JoinSqlRendererTest {

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
  private static final SqlRenderer JOIN_RENDERER =
      new StandardSqlRenderer(
          "join-test",
          StandardIdentifierRules.INSTANCE,
          DialectCapabilities.of(
              DialectFeature.SCHEMA_QUALIFIED_TABLES,
              DialectFeature.INNER_JOIN,
              DialectFeature.LEFT_JOIN,
              DialectFeature.RIGHT_JOIN,
              DialectFeature.FULL_JOIN,
              DialectFeature.CROSS_JOIN));

  @Test
  void rendersFiveJoinKindsAliasesSchemaAndParametersInAstOrder() {
    PetTable root = new PetTable().as("root_pet");
    PetTable inner = new PetTable().as("inner_pet");
    PetTable left = new PetTable().as("left_pet");
    PetTable right = new PetTable().as("right_pet");
    PetTable full = new PetTable().as("full_pet");
    PetTable cross = new PetTable().as("cross_pet");
    ParameterSlot<Long> innerParameter = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<Long> leftParameter = new ParameterSlot<>(1, Long.class, false);
    ParameterSlot<Long> rightParameter = new ParameterSlot<>(2, Long.class, false);
    ParameterSlot<Long> fullParameter = new ParameterSlot<>(3, Long.class, false);
    ParameterSlot<Long> whereParameter = new ParameterSlot<>(4, Long.class, false);
    FromClause from =
        new FromClause(
            root,
            List.of(
                new JoinClause(JoinType.INNER, inner, on(root, inner, innerParameter)),
                new JoinClause(JoinType.LEFT, left, on(inner, left, leftParameter)),
                new JoinClause(JoinType.RIGHT, right, on(left, right, rightParameter)),
                new JoinClause(JoinType.FULL, full, on(right, full, fullParameter)),
                new JoinClause(JoinType.CROSS, cross, null)));

    RenderedSql rendered =
        JOIN_RENDERER.render(
            new SelectStatement(
                List.of(root.name(), cross.name()), from, cross.id().eq(whereParameter)));

    assertEquals(
        "SELECT \"root_pet\".\"pet_name\", \"cross_pet\".\"pet_name\" "
            + "FROM \"shelter\".\"pet\" AS \"root_pet\" "
            + "INNER JOIN \"shelter\".\"pet\" AS \"inner_pet\" "
            + "ON \"root_pet\".\"id\" = \"inner_pet\".\"id\" AND \"inner_pet\".\"id\" = ? "
            + "LEFT JOIN \"shelter\".\"pet\" AS \"left_pet\" "
            + "ON \"inner_pet\".\"id\" = \"left_pet\".\"id\" AND \"left_pet\".\"id\" = ? "
            + "RIGHT JOIN \"shelter\".\"pet\" AS \"right_pet\" "
            + "ON \"left_pet\".\"id\" = \"right_pet\".\"id\" AND \"right_pet\".\"id\" = ? "
            + "FULL JOIN \"shelter\".\"pet\" AS \"full_pet\" "
            + "ON \"right_pet\".\"id\" = \"full_pet\".\"id\" AND \"full_pet\".\"id\" = ? "
            + "CROSS JOIN \"shelter\".\"pet\" AS \"cross_pet\" "
            + "WHERE \"cross_pet\".\"id\" = ?",
        rendered.sql());
    assertEquals(
        List.of(
            innerParameter,
            leftParameter,
            rightParameter,
            fullParameter,
            whereParameter),
        rendered.parameters());
  }

  @Test
  void countRendersTheSameFromClauseAndJoinParameters() {
    PetTable root = new PetTable();
    PetTable joined = new PetTable().as("joined_pet");
    ParameterSlot<Long> onParameter = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<Long> whereParameter = new ParameterSlot<>(1, Long.class, false);
    FromClause from =
        new FromClause(
            root,
            List.of(new JoinClause(JoinType.LEFT, joined, on(root, joined, onParameter))));

    RenderedSql rendered =
        JOIN_RENDERER.render(new CountAst(from, root.id().eq(whereParameter), null));

    assertEquals(
        "SELECT COUNT(*) FROM \"shelter\".\"pet\" "
            + "LEFT JOIN \"shelter\".\"pet\" AS \"joined_pet\" "
            + "ON \"pet\".\"id\" = \"joined_pet\".\"id\" AND \"joined_pet\".\"id\" = ? "
            + "WHERE \"pet\".\"id\" = ?",
        rendered.sql());
    assertEquals(List.of(onParameter, whereParameter), rendered.parameters());
  }

  @Test
  void rejectsAnUnsupportedJoinBeforeRenderingWithDialectAndPosition() {
    SqlRenderer innerOnly =
        new StandardSqlRenderer(
            "limited",
            StandardIdentifierRules.INSTANCE,
            DialectCapabilities.of(
                DialectFeature.SCHEMA_QUALIFIED_TABLES, DialectFeature.INNER_JOIN));
    PetTable root = new PetTable();
    PetTable inner = new PetTable().as("inner_pet");
    PetTable full = new PetTable().as("full_pet");
    FromClause from =
        new FromClause(
            root,
            List.of(
                new JoinClause(JoinType.INNER, inner, root.id().eq(inner.id())),
                new JoinClause(JoinType.FULL, full, inner.id().eq(full.id()))));

    SqlRenderException failure =
        assertThrows(
            SqlRenderException.class,
            () -> innerOnly.render(new SelectStatement(List.of(root.id()), from)));

    assertEquals(
        "dialect 'limited' does not support FULL JOIN at SELECT FROM join #2",
        failure.getMessage());
  }

  private static SqlPredicate on(
      PetTable left, PetTable right, ParameterSlot<Long> parameter) {
    return LogicalPredicate.and(
        List.of(left.id().eq(right.id()), right.id().eq(parameter)));
  }

  private record Pet(Long id, String name) {}

  private static final class PetTable extends TableExpression<Pet> {

    private final ColumnExpression<Pet, Long> id = column(ID);
    private final ColumnExpression<Pet, String> name = column(NAME);

    private PetTable() {
      super(PET);
    }

    private PetTable(Identifier alias) {
      super(PET, alias);
    }

    private ColumnExpression<Pet, Long> id() {
      return id;
    }

    private ColumnExpression<Pet, String> name() {
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
}
