package io.skis.dialect.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.DialectFeature;
import io.skis.dialect.RenderedSql;
import io.skis.dialect.SqlRenderException;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.TableExpression;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.skis.PetMeta;
import java.util.List;
import org.junit.jupiter.api.Test;

class H2JoinDialectTest {

  @Test
  void declaresAndRendersTheFourSupportedJoinKinds() {
    for (DialectFeature feature :
        List.of(
            DialectFeature.INNER_JOIN,
            DialectFeature.LEFT_JOIN,
            DialectFeature.RIGHT_JOIN,
            DialectFeature.CROSS_JOIN)) {
      assertTrue(H2Dialect.INSTANCE.capabilities().supports(feature));
    }
    assertFalse(H2Dialect.INSTANCE.capabilities().supports(DialectFeature.FULL_JOIN));

    PetAstTable root = PetAstTable.PET.as("root_pet");
    PetAstTable inner = PetAstTable.PET.as("inner_pet");
    PetAstTable left = PetAstTable.PET.as("left_pet");
    PetAstTable right = PetAstTable.PET.as("right_pet");
    PetAstTable cross = PetAstTable.PET.as("cross_pet");
    FromClause from =
        new FromClause(
            root,
            List.of(
                new JoinClause(JoinType.INNER, inner, root.id().eq(inner.id())),
                new JoinClause(JoinType.LEFT, left, inner.id().eq(left.id())),
                new JoinClause(JoinType.RIGHT, right, left.id().eq(right.id())),
                new JoinClause(JoinType.CROSS, cross, null)));

    RenderedSql rendered =
        H2Dialect.INSTANCE
            .renderer()
            .render(new SelectStatement(List.of(root.id(), cross.name()), from));

    assertEquals(
        "SELECT \"root_pet\".\"id\", \"cross_pet\".\"pet_name\" "
            + "FROM \"shelter\".\"pet\" AS \"root_pet\" "
            + "INNER JOIN \"shelter\".\"pet\" AS \"inner_pet\" "
            + "ON \"root_pet\".\"id\" = \"inner_pet\".\"id\" "
            + "LEFT JOIN \"shelter\".\"pet\" AS \"left_pet\" "
            + "ON \"inner_pet\".\"id\" = \"left_pet\".\"id\" "
            + "RIGHT JOIN \"shelter\".\"pet\" AS \"right_pet\" "
            + "ON \"left_pet\".\"id\" = \"right_pet\".\"id\" "
            + "CROSS JOIN \"shelter\".\"pet\" AS \"cross_pet\"",
        rendered.sql());
    assertTrue(rendered.parameters().isEmpty());
  }

  @Test
  void rejectsFullJoinWithDialectAndJoinPosition() {
    PetAstTable root = PetAstTable.PET;
    PetAstTable left = PetAstTable.PET.as("left_pet");
    PetAstTable full = PetAstTable.PET.as("full_pet");
    FromClause from =
        new FromClause(
            root,
            List.of(
                new JoinClause(JoinType.LEFT, left, root.id().eq(left.id())),
                new JoinClause(JoinType.FULL, full, left.id().eq(full.id()))));

    SelectStatement statement = new SelectStatement(List.of(root.id()), from);
    SqlRenderException validationFailure =
        assertThrows(SqlRenderException.class, () -> H2Dialect.INSTANCE.validate(statement));
    SqlRenderException renderingFailure =
        assertThrows(
            SqlRenderException.class,
            () -> H2Dialect.INSTANCE.renderer().render(statement));

    assertEquals(
        "dialect 'h2' does not support FULL JOIN at SELECT FROM join #2",
        validationFailure.getMessage());
    assertEquals(validationFailure.getMessage(), renderingFailure.getMessage());
  }

  private static final class PetAstTable extends TableExpression<Pet> {

    private static final PetAstTable PET = new PetAstTable();

    private final ColumnExpression<Pet, Long> id = column(PetMeta.ID);
    private final ColumnExpression<Pet, String> name = column(PetMeta.NAME);

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
