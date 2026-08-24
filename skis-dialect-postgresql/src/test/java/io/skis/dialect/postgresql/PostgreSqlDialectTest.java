package io.skis.dialect.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.DialectFeature;
import io.skis.dialect.RenderedSql;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.TableExpression;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.skis.PetMeta;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostgreSqlDialectTest {

  @Test
  void exposesTheInitialPostgreSqlContract() {
    PostgreSqlDialect dialect = PostgreSqlDialect.INSTANCE;

    assertEquals("postgresql", dialect.id());
    assertSame(PostgreSqlRenderer.INSTANCE, dialect.renderer());
    assertTrue(dialect.capabilities().supports(DialectFeature.SCHEMA_QUALIFIED_TABLES));
    assertFalse(dialect.capabilities().supports(DialectFeature.CATALOG_QUALIFIED_TABLES));
    assertEquals("\"select\"", dialect.identifierRules().quote("select"));
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
