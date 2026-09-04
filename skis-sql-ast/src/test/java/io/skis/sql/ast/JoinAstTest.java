package io.skis.sql.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

class JoinAstTest {

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

  @Test
  void assignsStableOccurrencesAndDefensivelyCopiesJoins() {
    PetTable root = new PetTable();
    PetTable parent = root.as("parent_pet");
    PetTable guardian = root.as("guardian_pet");
    List<JoinClause> joins = new ArrayList<>();
    joins.add(new JoinClause(JoinType.INNER, parent, root.id().eq(parent.id())));
    joins.add(new JoinClause(JoinType.LEFT, guardian, parent.id().eq(guardian.id())));

    FromClause fromClause = new FromClause(root, joins);
    joins.clear();

    assertEquals(2, fromClause.joins().size());
    assertEquals(
        List.of(0, 1, 2),
        fromClause.occurrences().stream().map(TableOccurrence::occurrenceOrdinal).toList());
    assertEquals(
        List.of("pet", "parent_pet", "guardian_pet"),
        fromClause.occurrences().stream().map(TableOccurrence::effectiveQualifier).toList());
    assertSame(root, fromClause.occurrences().get(0).table());
    assertSame(parent, fromClause.occurrenceOf(parent).orElseThrow().table());
    assertFalse(fromClause.occurrenceOf(root.as("parent_pet")).isPresent());
    assertThrows(UnsupportedOperationException.class, () -> fromClause.joins().clear());
    assertThrows(UnsupportedOperationException.class, () -> fromClause.occurrences().clear());
  }

  @Test
  void structureUsesCanonicalMetadataAliasOccurrenceAndJoinOrder() {
    PetTable firstRoot = new PetTable();
    PetTable firstOwner = firstRoot.as("owner_pet");
    FromClause first =
        new FromClause(
            firstRoot,
            List.of(
                new JoinClause(
                    JoinType.LEFT, firstOwner, firstRoot.id().eq(firstOwner.id()))));

    PetTable secondRoot = new PetTable();
    PetTable secondOwner = secondRoot.as("owner_pet");
    FromClause equivalent =
        new FromClause(
            secondRoot,
            List.of(
                new JoinClause(
                    JoinType.LEFT, secondOwner, secondRoot.id().eq(secondOwner.id()))));
    FromClause differentType =
        new FromClause(
            secondRoot,
            List.of(
                new JoinClause(
                    JoinType.INNER, secondOwner, secondRoot.id().eq(secondOwner.id()))));

    assertEquals(firstRoot, secondRoot);
    assertEquals(first, equivalent);
    assertEquals(first.hashCode(), equivalent.hashCode());
    assertEquals(first.occurrences(), equivalent.occurrences());
    assertNotEquals(first, differentType);
    assertEquals(
        new SelectStatement(List.of(firstOwner.name()), first),
        new SelectStatement(List.of(secondOwner.name()), equivalent));
  }

  @Test
  void enforcesJoinOnStructureAtConstruction() {
    PetTable right = new PetTable().as("right_pet");

    assertThrows(
        IllegalArgumentException.class,
        () -> new JoinClause(JoinType.CROSS, right, right.id().isNotNull()));
    for (JoinType type : List.of(JoinType.INNER, JoinType.LEFT, JoinType.RIGHT, JoinType.FULL)) {
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> new JoinClause(type, right, null));
      assertTrue(failure.getMessage().contains(type + " JOIN requires"));
    }
    assertTrue(new JoinClause(JoinType.CROSS, right, null).on().isEmpty());
  }

  @Test
  void rejectsRepeatedReferencesAndEffectiveQualifierCollisions() {
    PetTable root = new PetTable();

    IllegalArgumentException repeatedReference =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new FromClause(
                    root,
                    List.of(
                        new JoinClause(JoinType.INNER, root, root.id().eq(root.id())))));
    assertTrue(repeatedReference.getMessage().contains("registered more than once"));

    PetTable firstAlias = root.as("duplicate_alias");
    PetTable secondAlias = root.as("duplicate_alias");
    IllegalArgumentException duplicateAlias =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new FromClause(
                    firstAlias,
                    List.of(
                        new JoinClause(
                            JoinType.INNER,
                            secondAlias,
                            firstAlias.id().eq(secondAlias.id())))));
    assertTrue(duplicateAlias.getMessage().contains("effective table qualifier 'duplicate_alias'"));

    PetTable otherUnaliased = new PetTable();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FromClause(
                root,
                List.of(
                    new JoinClause(
                        JoinType.INNER, otherUnaliased, root.id().eq(otherUnaliased.id())))));
    PetTable physicalNameAlias = root.as("pet");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FromClause(
                root,
                List.of(
                    new JoinClause(
                        JoinType.INNER,
                        physicalNameAlias,
                        root.id().eq(physicalNameAlias.id())))));

    EntityMeta<Pet> otherSchemaMetadata =
        EntityMeta.simple(
            Pet.class,
            new TableMeta("", "archive", "pet"),
            List.of(ID, NAME),
            new PrimaryKeyMeta<>(List.of(ID)),
            false);
    PetTable samePhysicalNameInAnotherSchema = new PetTable(otherSchemaMetadata);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FromClause(
                root,
                List.of(
                    new JoinClause(
                        JoinType.INNER,
                        samePhysicalNameInAnotherSchema,
                        root.id().eq(samePhysicalNameInAnotherSchema.id())))));
  }

  @Test
  void validatesEachOnAgainstOnlyItsLeftScopeAndCurrentRightTable() {
    PetTable root = new PetTable();
    PetTable first = root.as("first_pet");
    PetTable second = root.as("second_pet");
    FromClause validFrom =
        new FromClause(
            root,
            List.of(
                new JoinClause(JoinType.INNER, first, root.id().eq(first.id())),
                new JoinClause(JoinType.LEFT, second, first.id().eq(second.id()))));

    SelectStatement valid =
        new SelectStatement(
            List.of(second.name()), validFrom, root.id().eq(second.id()));
    assertEquals(2, valid.joins().size());

    FromClause forwardReference =
        new FromClause(
            root,
            List.of(
                new JoinClause(JoinType.INNER, first, root.id().eq(second.id())),
                new JoinClause(JoinType.LEFT, second, first.id().eq(second.id()))));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SelectStatement(List.of(root.id()), forwardReference));
    assertTrue(failure.getMessage().contains("SELECT FROM join #1 ON"));
    assertTrue(failure.getMessage().contains("alias 'second_pet'"));
  }

  @Test
  void rejectsStructurallyEqualButDifferentTableReferencesInFinalClauses() {
    PetTable root = new PetTable();
    PetTable joined = root.as("owner_pet");
    PetTable impersonator = root.as("owner_pet");
    FromClause from =
        new FromClause(
            root,
            List.of(new JoinClause(JoinType.INNER, joined, root.id().eq(joined.id()))));

    assertEquals(joined, impersonator);
    IllegalArgumentException selectionFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SelectStatement(List.of(impersonator.name()), from));
    assertTrue(selectionFailure.getMessage().contains("SELECT column 'name'"));
    assertTrue(selectionFailure.getMessage().contains("object identity"));

    IllegalArgumentException emptyInFailure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SelectStatement(
                    List.of(root.id()),
                    from,
                    new InPredicate<>(impersonator.id(), List.of(), false)));
    assertTrue(emptyInFailure.getMessage().contains("WHERE column 'id'"));
  }

  @Test
  void finalScopeCoversSelectionWhereOrderBySeekAndCount() {
    PetTable root = new PetTable();
    PetTable joined = root.as("joined_pet");
    ParameterSlot<Long> anchor = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<Integer> limit = new ParameterSlot<>(1, Integer.class, false);
    FromClause from =
        new FromClause(
            root,
            List.of(new JoinClause(JoinType.RIGHT, joined, root.id().eq(joined.id()))));
    SelectStatement statement =
        new SelectStatement(
            false,
            List.of(joined.name()),
            List.of(new HiddenSelection(joined.id(), Identifier.of("joined_id"))),
            from,
            joined.name().isNotNull(),
            List.of(
                new OrderByItem(joined.id(), OrderDirection.ASC, NullOrder.DIALECT_DEFAULT)),
            new KeysetSeek(joined.id().gt(anchor), limit));
    CountAst count = new CountAst(from, joined.name().isNotNull(), joined.name());

    assertSame(from, statement.fromClause());
    assertSame(from, count.fromClause());
    assertEquals(statement.joins(), count.joins());
  }

  private record Pet(Long id, String name) {}

  private static final class PetTable extends TableExpression<Pet> {

    private final ColumnExpression<Pet, Long> id = column(ID);
    private final ColumnExpression<Pet, String> name = column(NAME);

    private PetTable() {
      super(PET);
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
    public PetTable as(String alias) {
      return new PetTable(Identifier.of(alias));
    }

    @Override
    public PetTable as(Identifier alias) {
      return new PetTable(alias);
    }
  }
}
