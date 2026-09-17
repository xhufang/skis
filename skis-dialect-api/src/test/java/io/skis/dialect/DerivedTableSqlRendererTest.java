package io.skis.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedOutputColumn;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.TableExpression;
import java.util.List;
import org.junit.jupiter.api.Test;

class DerivedTableSqlRendererTest {

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
  private static final SqlRenderer RENDERER =
      renderer(
          "derived-test",
          DialectFeature.SCHEMA_QUALIFIED_TABLES,
          DialectFeature.DERIVED_TABLE,
          DialectFeature.INNER_JOIN,
          DialectFeature.CROSS_JOIN);

  @Test
  void rendersDerivedRootOutputAliasesAndNestedParametersInSqlOrder() {
    PetTable inner = new PetTable().as("inner_pet");
    ParameterSlot<String> innerName = new ParameterSlot<>(0, String.class, true);
    ParameterSlot<Long> outerMinimum = new ParameterSlot<>(1, Long.class, false);
    DerivedRelationReference reference = reference("visible_pets");
    DerivedRelationSource source =
        new DerivedRelationSource(
            new SelectStatement(
                List.of(inner.id(), inner.name()), inner, inner.name().eq(innerName)),
            reference);
    DerivedColumnExpression<Long> id = new DerivedColumnExpression<>(reference, 0);
    DerivedColumnExpression<String> name = new DerivedColumnExpression<>(reference, 1);

    RenderedSql rendered =
        RENDERER.render(
            new SelectStatement(List.of(name), FromClause.of(source), id.gt(outerMinimum)));

    assertEquals(
        "SELECT \"visible_pets\".\"display_name\" FROM "
            + "(SELECT \"inner_pet\".\"id\" AS \"pet_id\", "
            + "\"inner_pet\".\"pet_name\" AS \"display_name\" "
            + "FROM \"shelter\".\"pet\" AS \"inner_pet\" "
            + "WHERE \"inner_pet\".\"pet_name\" = ?) AS \"visible_pets\" "
            + "WHERE \"visible_pets\".\"pet_id\" > ?",
        rendered.sql());
    assertEquals(List.of(innerName, outerMinimum), rendered.parameters());
  }

  @Test
  void rendersDerivedJoinAndIndependentAliasesOfOneInnerSelect() {
    PetTable root = new PetTable().as("root_pet");
    PetTable inner = new PetTable().as("inner_pet");
    SelectStatement child = new SelectStatement(List.of(inner.id(), inner.name()), inner);
    DerivedRelationReference firstReference = reference("first_pet");
    DerivedRelationReference secondReference = reference("second_pet");
    DerivedRelationSource first = new DerivedRelationSource(child, firstReference);
    DerivedRelationSource second = new DerivedRelationSource(child, secondReference);
    DerivedColumnExpression<Long> firstId =
        new DerivedColumnExpression<>(firstReference, 0);
    DerivedColumnExpression<String> secondName =
        new DerivedColumnExpression<>(secondReference, 1);
    FromClause from =
        new FromClause(
            root,
            List.of(
                new JoinClause(JoinType.INNER, first, root.id().eq(firstId)),
                new JoinClause(JoinType.CROSS, second, null)));

    RenderedSql rendered =
        RENDERER.render(new SelectStatement(List.of(firstId, secondName), from));

    assertEquals(
        "SELECT \"first_pet\".\"pet_id\", \"second_pet\".\"display_name\" "
            + "FROM \"shelter\".\"pet\" AS \"root_pet\" INNER JOIN "
            + "(SELECT \"inner_pet\".\"id\" AS \"pet_id\", "
            + "\"inner_pet\".\"pet_name\" AS \"display_name\" "
            + "FROM \"shelter\".\"pet\" AS \"inner_pet\") AS \"first_pet\" "
            + "ON \"root_pet\".\"id\" = \"first_pet\".\"pet_id\" CROSS JOIN "
            + "(SELECT \"inner_pet\".\"id\" AS \"pet_id\", "
            + "\"inner_pet\".\"pet_name\" AS \"display_name\" "
            + "FROM \"shelter\".\"pet\" AS \"inner_pet\") AS \"second_pet\"",
        rendered.sql());
  }

  @Test
  void rejectsDerivedTablesWhenTheDialectDidNotDeclareTheCapability() {
    PetTable inner = new PetTable();
    DerivedRelationReference reference = reference("visible_pets");
    DerivedRelationSource source =
        new DerivedRelationSource(
            new SelectStatement(List.of(inner.id(), inner.name()), inner), reference);
    SqlRenderer limited =
        renderer("limited", DialectFeature.SCHEMA_QUALIFIED_TABLES);

    SqlRenderException failure =
        assertThrows(
            SqlRenderException.class,
            () ->
                limited.render(
                    new SelectStatement(
                        List.of(new DerivedColumnExpression<Long>(reference, 0)),
                        FromClause.of(source))));

    assertTrue(failure.getMessage().contains("missing DERIVED_TABLE"));
    assertTrue(failure.getMessage().contains("$/JOIN source[0]#0"));
  }

  private static SqlRenderer renderer(String id, DialectFeature... features) {
    return new StandardSqlRenderer(
        id, StandardIdentifierRules.INSTANCE, DialectCapabilities.of(features));
  }

  private static DerivedRelationReference reference(String alias) {
    return new DerivedRelationReference(
        Identifier.of(alias),
        List.of(
            new DerivedOutputColumn(
                Identifier.of("pet_id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL),
            new DerivedOutputColumn(
                Identifier.of("display_name"),
                String.class,
                SqlType.VARCHAR,
                Nullability.NULLABLE)));
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
