package io.skis.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.metadata.VersionMeta;
import io.skis.metadata.VersionStrategy;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.TableExpression;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandardSqlRendererTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("pet_name", true));
  private static final PropertyMeta<Pet, Long> VERSION =
      new PropertyMeta<>(2, "version", Long.class, ColumnMeta.of("version", false));
  private static final EntityMeta<Pet> PET = metadata(new TableMeta("", "shelter", "pet"));
  private static final EntityMeta<Pet> CATALOG_PET =
      metadata(new TableMeta("animal_db", "shelter", "pet"));
  private static final EntityMeta<Pet> OTHER_PET = metadata(TableMeta.of("other_pet"));
  private static final EntityMeta<Pet> SQL_LOOKING_PET =
      metadata(TableMeta.of("pet; DROP TABLE audit; --"));
  private static final SqlRenderer RENDERER =
      new StandardSqlRenderer(
          "test",
          StandardIdentifierRules.INSTANCE,
          DialectCapabilities.of(DialectFeature.SCHEMA_QUALIFIED_TABLES));

  @Test
  void preservesPlaceholderEncounterOrderWithoutParameterValues() {
    PetTable pet = new PetTable(PET);
    ParameterSlot<String> selectedName = new ParameterSlot<>(1, String.class, true);
    ParameterSlot<Long> id = new ParameterSlot<>(0, Long.class, false);
    SelectStatement statement =
        new SelectStatement(List.of(pet.id(), selectedName), pet, pet.id().eq(id));

    RenderedSql rendered = RENDERER.render(statement);

    assertEquals(
        "SELECT \"pet\".\"id\", ? FROM \"shelter\".\"pet\" WHERE \"pet\".\"id\" = ?",
        rendered.sql());
    assertEquals(List.of(selectedName, id), rendered.parameters());
  }

  @Test
  void rejectsCatalogInsteadOfSilentlyDroppingIt() {
    PetTable pet = new PetTable(CATALOG_PET);

    SqlRenderException failure =
        assertThrows(
            SqlRenderException.class,
            () -> RENDERER.render(new SelectStatement(List.of(pet.id()), pet)));

    assertTrue(failure.getMessage().contains("catalog-qualified table"));
  }

  @Test
  void rejectsColumnsOutsideTheOnlyFromTable() {
    PetTable pet = new PetTable(PET);
    PetTable other = new PetTable(OTHER_PET);

    SqlRenderException failure =
        assertThrows(
            SqlRenderException.class,
            () -> RENDERER.render(new SelectStatement(List.of(other.name()), pet)));

    assertTrue(failure.getMessage().contains("outside the single FROM table"));
  }

  @Test
  void quotesSqlLookingMetadataAsOneIdentifier() {
    PetTable pet = new PetTable(SQL_LOOKING_PET);

    RenderedSql rendered = RENDERER.render(new SelectStatement(List.of(pet.id()), pet));

    assertEquals(
        "SELECT \"pet; DROP TABLE audit; --\".\"id\" FROM \"pet; DROP TABLE audit; --\"",
        rendered.sql());
    assertEquals(0, rendered.parameterCount());
  }

  @Test
  void rendersPortableSingleEntityMutationSql() {
    PetTable pet = new PetTable(PET);
    ParameterSlot<Long> insertId = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<String> insertName = new ParameterSlot<>(1, String.class, true);
    ParameterSlot<Long> insertVersion = new ParameterSlot<>(2, Long.class, false);

    RenderedSql insert =
        RENDERER.render(
            new InsertStatement(
                pet,
                List.of(pet.id(), pet.name(), pet.version()),
                List.of(insertId, insertName, insertVersion)));

    assertEquals(
        "INSERT INTO \"shelter\".\"pet\" (\"id\", \"pet_name\", \"version\") VALUES (?, ?, ?)",
        insert.sql());
    assertEquals(List.of(insertId, insertName, insertVersion), insert.parameters());

    ParameterSlot<String> updateName = new ParameterSlot<>(0, String.class, true);
    ParameterSlot<Long> updateId = new ParameterSlot<>(1, Long.class, false);
    ParameterSlot<Long> expectedVersion = new ParameterSlot<>(2, Long.class, false);
    RenderedSql update =
        RENDERER.render(
            new UpdateStatement(
                pet,
                List.of(
                    new UpdateAssignment<>(pet.name(), updateName),
                    new UpdateAssignment<>(pet.version(), new IncrementExpression<>(pet.version()))),
                LogicalPredicate.and(
                    List.of(pet.id().eq(updateId), pet.version().eq(expectedVersion)))));

    assertEquals(
        "UPDATE \"shelter\".\"pet\" SET \"pet_name\" = ?, \"version\" = \"version\" + 1 WHERE \"id\" = ? AND \"version\" = ?",
        update.sql());
    assertEquals(List.of(updateName, updateId, expectedVersion), update.parameters());

    ParameterSlot<Long> deleteId = new ParameterSlot<>(0, Long.class, false);
    RenderedSql delete =
        RENDERER.render(new DeleteStatement(pet, pet.id().eq(deleteId)));

    assertEquals("DELETE FROM \"shelter\".\"pet\" WHERE \"id\" = ?", delete.sql());
    assertEquals(List.of(deleteId), delete.parameters());
  }

  @Test
  void rejectsUnknownStatementNodesWithDialectContext() {
    StatementAst unknown = new StatementAst() {};

    SqlRenderException failure =
        assertThrows(SqlRenderException.class, () -> RENDERER.render(unknown));

    assertTrue(failure.getMessage().contains("dialect 'test'"));
    assertTrue(failure.getMessage().contains(unknown.getClass().getName()));
  }

  private static EntityMeta<Pet> metadata(TableMeta table) {
    return EntityMeta.simple(
        Pet.class,
        table,
        List.of(ID, NAME, VERSION),
        new PrimaryKeyMeta<>(List.of(ID)),
        new VersionMeta<>(VERSION, VersionStrategy.NUMERIC_INCREMENT),
        false);
  }

  private record Pet(Long id, String name, Long version) {}

  private static final class PetTable extends TableExpression<Pet> {

    private final ColumnExpression<Pet, Long> id = column(ID);
    private final ColumnExpression<Pet, String> name = column(NAME);
    private final ColumnExpression<Pet, Long> version = column(VERSION);

    private PetTable(EntityMeta<Pet> entity) {
      super(entity);
    }

    private PetTable(EntityMeta<Pet> entity, Identifier alias) {
      super(entity, alias);
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
    public PetTable as(Identifier alias) {
      return new PetTable(entity(), alias);
    }
  }
}
