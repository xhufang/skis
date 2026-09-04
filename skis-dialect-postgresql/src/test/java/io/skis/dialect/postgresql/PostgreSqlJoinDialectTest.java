package io.skis.dialect.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.DialectFeature;
import io.skis.dialect.RenderedSql;
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

class PostgreSqlJoinDialectTest {

  @Test
  void declaresAndRendersAllFiveJoinKinds() {
    for (DialectFeature feature :
        List.of(
            DialectFeature.INNER_JOIN,
            DialectFeature.LEFT_JOIN,
            DialectFeature.RIGHT_JOIN,
            DialectFeature.FULL_JOIN,
            DialectFeature.CROSS_JOIN)) {
      assertTrue(PostgreSqlDialect.INSTANCE.capabilities().supports(feature));
    }

    PetAstTable root = PetAstTable.PET.as("root_pet");
    PetAstTable inner = PetAstTable.PET.as("inner_pet");
    PetAstTable left = PetAstTable.PET.as("left_pet");
    PetAstTable right = PetAstTable.PET.as("right_pet");
    PetAstTable full = PetAstTable.PET.as("full_pet");
    PetAstTable cross = PetAstTable.PET.as("cross_pet");
    FromClause from =
        new FromClause(
            root,
            List.of(
                new JoinClause(JoinType.INNER, inner, root.id().eq(inner.id())),
                new JoinClause(JoinType.LEFT, left, inner.id().eq(left.id())),
                new JoinClause(JoinType.RIGHT, right, left.id().eq(right.id())),
                new JoinClause(JoinType.FULL, full, right.id().eq(full.id())),
                new JoinClause(JoinType.CROSS, cross, null)));

    RenderedSql rendered =
        PostgreSqlDialect.INSTANCE
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
            + "FULL JOIN \"shelter\".\"pet\" AS \"full_pet\" "
            + "ON \"right_pet\".\"id\" = \"full_pet\".\"id\" "
            + "CROSS JOIN \"shelter\".\"pet\" AS \"cross_pet\"",
        rendered.sql());
    assertTrue(rendered.parameters().isEmpty());
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
