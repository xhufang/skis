package io.skis.sql.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlAstStructureTest {

  private static final PropertyMeta<Pet, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("pet_id", false));
  private static final PropertyMeta<Pet, String> NAME =
      new PropertyMeta<>(1, "name", String.class, ColumnMeta.of("pet_name", true));
  private static final EntityMeta<Pet> PET =
      EntityMeta.simple(
          Pet.class,
          new TableMeta("", "shelter", "pet"),
          List.of(ID, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);

  @Test
  void nodesHaveStableStructuralEqualityAndHashCodes() {
    PetTable firstTable = new PetTable();
    PetTable equivalentTable = new PetTable();
    ParameterSlot<Long> firstSlot = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<Long> equivalentSlot = new ParameterSlot<>(0, Long.class, false);
    SelectStatement first =
        new SelectStatement(
            List.of(firstTable.id(), firstTable.name()), firstTable, firstTable.id().eq(firstSlot));
    SelectStatement equivalent =
        new SelectStatement(
            List.of(equivalentTable.id(), equivalentTable.name()),
            equivalentTable,
            equivalentTable.id().eq(equivalentSlot));

    assertEquals(firstTable, equivalentTable);
    assertEquals(firstTable.hashCode(), equivalentTable.hashCode());
    assertEquals(first, equivalent);
    assertEquals(first.hashCode(), equivalent.hashCode());
    assertNotEquals(first, new SelectStatement(List.of(firstTable.name()), firstTable));
    assertNotEquals(firstTable, firstTable.as("p"));
  }

  @Test
  void keepsDistinctMetadataSymbolsOutOfTheSameStructureKey() {
    EntityMeta<Pet> distinctMetadata =
        EntityMeta.simple(
            Pet.class,
            new TableMeta("", "shelter", "pet"),
            List.of(ID, NAME),
            new PrimaryKeyMeta<>(List.of(ID)),
            false);

    assertNotEquals(new PetTable(), new PetTable(distinctMetadata));
  }

  @Test
  void expressionsExposeTypeAndNullability() {
    PetTable table = new PetTable();
    ParameterSlot<Long> requiredId = new ParameterSlot<>(0, Long.class, false);

    assertEquals(Long.class, table.id().javaType());
    assertFalse(table.id().nullable());
    assertEquals(String.class, table.name().javaType());
    assertTrue(table.name().nullable());
    assertFalse(table.id().eq(requiredId).nullable());
    assertTrue(table.name().eq(new ParameterSlot<>(1, String.class, false)).nullable());
  }

  @Test
  void normalizesPrimitiveParameterTypesToBoxedAstTypes() {
    assertEquals(Boolean.class, new ParameterSlot<>(0, boolean.class, false).javaType());
    assertEquals(Byte.class, new ParameterSlot<>(0, byte.class, false).javaType());
    assertEquals(Short.class, new ParameterSlot<>(0, short.class, false).javaType());
    assertEquals(Integer.class, new ParameterSlot<>(0, int.class, false).javaType());
    assertEquals(Long.class, new ParameterSlot<>(0, long.class, false).javaType());
    assertEquals(Float.class, new ParameterSlot<>(0, float.class, false).javaType());
    assertEquals(Double.class, new ParameterSlot<>(0, double.class, false).javaType());
    assertEquals(Character.class, new ParameterSlot<>(0, char.class, false).javaType());

    PetTable table = new PetTable();
    ParameterSlot<Long> primitiveId = new ParameterSlot<>(0, long.class, false);

    assertEquals(
        table.id().eq(new ParameterSlot<>(0, Long.class, false)), table.id().eq(primitiveId));
  }

  @Test
  void selectDefensivelyCopiesItsExpressionList() {
    PetTable table = new PetTable();
    List<SqlExpression<?>> selections = new ArrayList<>();
    selections.add(table.id());

    SelectStatement statement = new SelectStatement(selections, table);
    selections.add(table.name());

    assertEquals(List.of(table.id()), statement.selections());
    assertThrows(UnsupportedOperationException.class, () -> statement.selections().clear());
  }

  @Test
  void permitsRepeatedParameterOrdinalsWithTheSameDescriptor() {
    PetTable table = new PetTable();
    ParameterSlot<Long> first = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<Long> repeated = new ParameterSlot<>(0, long.class, false);

    assertEquals(
        new SelectStatement(List.of(first), table, table.id().eq(first)),
        new SelectStatement(List.of(first), table, table.id().eq(repeated)));
  }

  @Test
  void rejectsConflictingDescriptorsForTheSameParameterOrdinal() {
    PetTable table = new PetTable();
    ParameterSlot<Long> requiredId = new ParameterSlot<>(0, Long.class, false);

    IllegalArgumentException typeConflict =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SelectStatement(
                    List.of(new ParameterSlot<>(0, String.class, false)),
                    table,
                    table.id().eq(requiredId)));
    IllegalArgumentException nullabilityConflict =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SelectStatement(
                    List.of(new ParameterSlot<>(0, Long.class, true)),
                    table,
                    table.id().eq(requiredId)));

    assertTrue(typeConflict.getMessage().contains("parameter ordinal 0"));
    assertTrue(nullabilityConflict.getMessage().contains("parameter ordinal 0"));
  }

  @Test
  void appliesParameterSlotConsistencyRulesToEveryMutationStatement() {
    PetTable table = new PetTable();
    ParameterSlot<Long> id = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<String> conflictingName = new ParameterSlot<>(0, String.class, true);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InsertStatement(
                table, List.of(table.id(), table.name()), List.of(id, conflictingName)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new UpdateStatement(
                table,
                List.of(new UpdateAssignment<>(table.name(), conflictingName)),
                table.id().eq(id)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DeleteStatement(
                table,
                LogicalPredicate.and(
                    List.of(table.id().eq(id), table.name().eq(conflictingName)))));
  }

  @Test
  void rejectsParameterOrdinalGapsInQueryAndMutationStatements() {
    PetTable table = new PetTable();
    ParameterSlot<Long> gappedId = new ParameterSlot<>(1, Long.class, false);
    ParameterSlot<String> gappedName = new ParameterSlot<>(2, String.class, true);
    ParameterSlot<Long> firstId = new ParameterSlot<>(0, Long.class, false);

    assertThrows(
        IllegalArgumentException.class,
        () -> new SelectStatement(List.of(table.id()), table, table.id().eq(gappedId)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InsertStatement(table, List.of(table.id()), List.of(gappedId)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new UpdateStatement(
                table,
                List.of(new UpdateAssignment<>(table.name(), gappedName)),
                table.id().eq(firstId)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DeleteStatement(table, table.id().eq(gappedId)));
  }

  @Test
  void rejectsInvalidStatementAndParameterShapes() {
    PetTable table = new PetTable();

    assertThrows(IllegalArgumentException.class, () -> new ParameterSlot<>(-1, Long.class, false));
    assertThrows(IllegalArgumentException.class, () -> new ParameterSlot<>(0, Void.class, false));
    assertThrows(IllegalArgumentException.class, () -> new ParameterSlot<>(0, int.class, true));
    assertThrows(IllegalArgumentException.class, () -> new SelectStatement(List.of(), table));
    assertThrows(
        NullPointerException.class,
        () -> new SelectStatement(java.util.Arrays.asList(table.id(), null), table));
  }

  private record Pet(Long id, String name) {}

  private static final class PetTable extends TableExpression<Pet> {

    private final ColumnExpression<Pet, Long> id = column(ID);
    private final ColumnExpression<Pet, String> name = column(NAME);

    private PetTable() {
      this(PET);
    }

    private PetTable(EntityMeta<Pet> entity) {
      super(entity);
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
    public PetTable as(Identifier alias) {
      return new PetTable(alias);
    }
  }
}
