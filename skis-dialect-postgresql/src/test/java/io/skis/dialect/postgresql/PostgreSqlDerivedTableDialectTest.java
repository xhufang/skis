package io.skis.dialect.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.DialectFeature;
import io.skis.dialect.RenderedSql;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedOutputColumn;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.TableExpression;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.skis.PetMeta;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostgreSqlDerivedTableDialectTest {

  @Test
  void declaresAndRendersDerivedTablesWithExplicitOutputAliases() {
    PetAstTable inner = new PetAstTable(Identifier.of("inner_pet"));
    ParameterSlot<String> name = new ParameterSlot<>(0, String.class, true);
    DerivedRelationReference reference = reference();
    DerivedRelationSource source =
        new DerivedRelationSource(
            new SelectStatement(
                List.of(inner.id(), inner.name()), inner, inner.name().eq(name)),
            reference);

    RenderedSql rendered =
        PostgreSqlDialect.INSTANCE
            .renderer()
            .render(
                new SelectStatement(
                    List.of(new DerivedColumnExpression<Long>(reference, 0)),
                    FromClause.of(source)));

    assertTrue(
        PostgreSqlDialect.INSTANCE.capabilities().supports(DialectFeature.DERIVED_TABLE));
    assertEquals(
        "SELECT \"visible_pets\".\"pet_id\" FROM "
            + "(SELECT \"inner_pet\".\"id\" AS \"pet_id\", "
            + "\"inner_pet\".\"pet_name\" AS \"display_name\" "
            + "FROM \"shelter\".\"pet\" AS \"inner_pet\" "
            + "WHERE \"inner_pet\".\"pet_name\" = ?) AS \"visible_pets\"",
        rendered.sql());
    assertEquals(List.of(name), rendered.parameters());
  }

  private static DerivedRelationReference reference() {
    return new DerivedRelationReference(
        Identifier.of("visible_pets"),
        List.of(
            new DerivedOutputColumn(
                Identifier.of("pet_id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL),
            new DerivedOutputColumn(
                Identifier.of("display_name"),
                String.class,
                SqlType.VARCHAR,
                Nullability.NULLABLE)));
  }

  private static final class PetAstTable extends TableExpression<Pet> {

    private final ColumnExpression<Pet, Long> id = column(PetMeta.ID);
    private final ColumnExpression<Pet, String> name = column(PetMeta.NAME);

    private PetAstTable(Identifier alias) {
      super(PetMeta.ENTITY, alias);
    }

    private ColumnExpression<Pet, Long> id() {
      return id;
    }

    private ColumnExpression<Pet, String> name() {
      return name;
    }
  }
}
