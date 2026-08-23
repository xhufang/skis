package io.skis.dialect.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.DialectFeature;
import io.skis.dialect.RenderedSql;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.testmodel.pet.skis.PetTable;
import java.util.List;
import org.junit.jupiter.api.Test;

class H2DialectTest {

  @Test
  void exposesTheInitialH2Contract() {
    H2Dialect dialect = H2Dialect.INSTANCE;

    assertEquals("h2", dialect.id());
    assertSame(H2Renderer.INSTANCE, dialect.renderer());
    assertTrue(dialect.capabilities().supports(DialectFeature.SCHEMA_QUALIFIED_TABLES));
    assertFalse(dialect.capabilities().supports(DialectFeature.CATALOG_QUALIFIED_TABLES));
    assertEquals("\"select\"", dialect.identifierRules().quote("select"));
  }

  @Test
  void rendersSingleTableSelectGoldenSql() {
    PetTable pet = PetTable.PET;
    ParameterSlot<Long> id = new ParameterSlot<>(0, Long.class, false);
    SelectStatement statement =
        new SelectStatement(List.of(pet.id(), pet.name()), pet, pet.id().eq(id));

    RenderedSql rendered = H2Dialect.INSTANCE.renderer().render(statement);

    assertEquals(
        "SELECT \"pet\".\"id\", \"pet\".\"pet_name\" FROM \"shelter\".\"pet\" WHERE \"pet\".\"id\" = ?",
        rendered.sql());
    assertEquals(List.of(id), rendered.parameters());
  }

  @Test
  void rendersAliasesInSelectedColumnsAndPredicates() {
    PetTable pet = PetTable.PET.as("p");
    ParameterSlot<Long> id = new ParameterSlot<>(0, Long.class, false);

    RenderedSql rendered =
        H2Dialect.INSTANCE
            .renderer()
            .render(new SelectStatement(List.of(pet.name()), pet, pet.id().eq(id)));

    assertEquals(
        "SELECT \"p\".\"pet_name\" FROM \"shelter\".\"pet\" AS \"p\" WHERE \"p\".\"id\" = ?",
        rendered.sql());
    assertEquals(List.of(id), rendered.parameters());
  }
}
