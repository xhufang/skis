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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    assertEquals(SqlType.BIGINT, table.id().sqlType());
    assertEquals(Nullability.NON_NULL, table.id().nullability());
    assertFalse(table.id().nullable());
    assertEquals(String.class, table.name().javaType());
    assertEquals(SqlType.VARCHAR, table.name().sqlType());
    assertEquals(Nullability.NULLABLE, table.name().nullability());
    assertTrue(table.name().nullable());
    assertFalse(table.id().eq(requiredId).nullable());
    assertTrue(table.name().eq(new ParameterSlot<>(1, String.class, false)).nullable());
    assertEquals(Nullability.NON_NULL, table.name().isNull().nullability());
  }

  @Test
  void mapsJavaRepresentationsAndDefinesPortableTypeFamilies() {
    assertEquals(SqlType.BOOLEAN, SqlType.fromJavaType(boolean.class));
    assertEquals(SqlType.INTEGER, SqlType.fromJavaType(Integer.class));
    assertEquals(SqlType.DECIMAL, SqlType.fromJavaType(BigDecimal.class));
    assertEquals(SqlType.VARCHAR, SqlType.fromJavaType(String.class));
    assertEquals(SqlType.VARBINARY, SqlType.fromJavaType(byte[].class));
    assertEquals(SqlType.UUID, SqlType.fromJavaType(UUID.class));
    assertEquals(SqlType.DATE, SqlType.fromJavaType(LocalDate.class));
    assertEquals(SqlType.TIME_WITH_TIME_ZONE, SqlType.fromJavaType(OffsetTime.class));
    assertEquals(SqlType.TIMESTAMP_WITH_TIME_ZONE, SqlType.fromJavaType(Instant.class));
    assertEquals(SqlType.OTHER, SqlType.fromJavaType(Object.class));

    assertTrue(SqlType.INTEGER.equalityCompatibleWith(SqlType.DECIMAL));
    assertTrue(SqlType.VARCHAR.orderingCompatibleWith(SqlType.CHARACTER));
    assertTrue(SqlType.VARCHAR.supportsLike());
    assertFalse(SqlType.BOOLEAN.isOrderable());
    assertFalse(SqlType.UUID.equalityCompatibleWith(SqlType.VARCHAR));
    assertTrue(SqlType.VARCHAR.castableTo(SqlType.BIGINT));
    assertFalse(SqlType.BOOLEAN.castableTo(SqlType.BIGINT));
    assertFalse(SqlType.VARBINARY.isCastTarget());
  }

  @Test
  void validatesPredicateTypeCompatibilityAndNullabilityPropagation() {
    PetTable table = new PetTable();
    ParameterSlot<Long> lower = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<Long> upper = new ParameterSlot<>(1, Long.class, false);
    ParameterSlot<String> pattern = new ParameterSlot<>(2, String.class, false);

    assertEquals(
        Nullability.NON_NULL, new BetweenPredicate<>(table.id(), lower, upper).nullability());
    assertEquals(Nullability.NULLABLE, new LikePredicate(table.name(), pattern).nullability());
    assertEquals(Nullability.NULLABLE, new NotPredicate(table.name().eq(pattern)).nullability());
    assertThrows(
        IllegalArgumentException.class,
        () -> new LikePredicate(table.id(), new ParameterSlot<>(3, Long.class, false)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            table
                .id()
                .gt(new ParameterSlot<>(3, Long.class, SqlType.VARCHAR, Nullability.NON_NULL)));
  }

  @Test
  void membershipDefensivelyCopiesCandidatesAndDefinesEmptyNullability() {
    PetTable table = new PetTable();
    List<SqlExpression<Long>> candidates = new ArrayList<>();
    candidates.add(new ParameterSlot<>(0, Long.class, false));
    InPredicate<Long> predicate = new InPredicate<>(table.id(), candidates, false);
    candidates.clear();

    assertEquals(1, predicate.candidates().size());
    assertThrows(UnsupportedOperationException.class, () -> predicate.candidates().clear());
    assertEquals(
        Nullability.NON_NULL, new InPredicate<>(table.name(), List.of(), false).nullability());
  }

  @Test
  void standardExpressionsAreTypedImmutableAndPropagateNullability() {
    PetTable table = new PetTable();
    ParameterSlot<Long> addend = new ParameterSlot<>(0, Long.class, false);
    ArithmeticExpression<Long> arithmetic =
        new ArithmeticExpression<>(table.id(), ArithmeticOperator.ADD, addend);
    List<SqlExpression<String>> concatOperands = new ArrayList<>();
    concatOperands.add(table.name());
    concatOperands.add(new ParameterSlot<>(1, String.class, false));
    ConcatExpression concat = new ConcatExpression(concatOperands);
    List<CaseWhen<String>> branches = new ArrayList<>();
    branches.add(
        new CaseWhen<>(table.id().gt(new ParameterSlot<>(2, Long.class, false)), table.name()));
    CaseExpression<String> caseExpression =
        new CaseExpression<>(branches, new ParameterSlot<>(3, String.class, false));
    CastExpression<String> cast = new CastExpression<>(table.id(), String.class);
    List<SqlExpression<String>> coalesceOperands = new ArrayList<>();
    coalesceOperands.add(table.name());
    coalesceOperands.add(new ParameterSlot<>(4, String.class, false));
    CoalesceExpression<String> coalesce = new CoalesceExpression<>(coalesceOperands);

    concatOperands.clear();
    branches.clear();
    coalesceOperands.clear();

    assertEquals(Long.class, arithmetic.javaType());
    assertEquals(SqlType.BIGINT, arithmetic.sqlType());
    assertEquals(Nullability.NON_NULL, arithmetic.nullability());
    assertEquals(
        arithmetic, new ArithmeticExpression<>(table.id(), ArithmeticOperator.ADD, addend));
    assertEquals(2, concat.operands().size());
    assertThrows(UnsupportedOperationException.class, () -> concat.operands().clear());
    assertEquals(SqlType.VARCHAR, concat.sqlType());
    assertEquals(Nullability.NULLABLE, concat.nullability());
    assertEquals(1, caseExpression.branches().size());
    assertThrows(UnsupportedOperationException.class, () -> caseExpression.branches().clear());
    assertEquals(Nullability.NULLABLE, caseExpression.nullability());
    assertEquals(
        Nullability.NULLABLE,
        new CaseExpression<>(List.of(new CaseWhen<>(table.id().isNotNull(), table.id())))
            .nullability());
    assertEquals(String.class, cast.javaType());
    assertEquals(SqlType.VARCHAR, cast.sqlType());
    assertEquals(Nullability.NON_NULL, cast.nullability());
    assertEquals(Nullability.NON_NULL, coalesce.nullability());
    assertEquals(2, coalesce.operands().size());
    assertThrows(UnsupportedOperationException.class, () -> coalesce.operands().clear());
    assertEquals(
        Nullability.NULLABLE,
        new CoalesceExpression<>(List.of(table.name(), LiteralExpression.nullLiteral(String.class)))
            .nullability());
    assertEquals(Boolean.class, LiteralExpression.trueLiteral().javaType());
    assertEquals(Long.class, LiteralExpression.one(long.class).javaType());
    assertEquals(LiteralExpression.one(Long.class), LiteralExpression.one(long.class));
    assertNotEquals(LiteralExpression.zero(Long.class), LiteralExpression.one(Long.class));
    assertEquals(Nullability.NULLABLE, LiteralExpression.nullLiteral(String.class).nullability());
  }

  @Test
  void semanticValidatorTraversesEveryStandardExpressionAndParameterSlot() {
    PetTable table = new PetTable();
    ParameterSlot<Long> addend = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<String> suffix = new ParameterSlot<>(1, String.class, false);
    ParameterSlot<Long> threshold = new ParameterSlot<>(2, Long.class, false);
    ParameterSlot<String> otherwise = new ParameterSlot<>(3, String.class, false);
    ParameterSlot<String> fallback = new ParameterSlot<>(4, String.class, false);

    SelectStatement statement =
        new SelectStatement(
            List.of(
                new ArithmeticExpression<>(table.id(), ArithmeticOperator.ADD, addend),
                new ConcatExpression(List.of(table.name(), suffix)),
                new CaseExpression<>(
                    List.of(new CaseWhen<>(table.id().gt(threshold), table.name())), otherwise),
                new CastExpression<>(table.id(), String.class),
                new CoalesceExpression<>(List.of(table.name(), fallback))),
            table);

    assertEquals(5, statement.selections().size());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SelectStatement(
                List.of(
                    new CoalesceExpression<>(
                        List.of(table.name(), new ParameterSlot<>(1, String.class, false)))),
                table));
  }

  @Test
  void centralizedRulesRejectInvalidStandardExpressionTypesAndNullComparisons() {
    PetTable table = new PetTable();

    assertThrows(IllegalArgumentException.class, () -> new ConcatExpression(List.of(table.name())));
    assertThrows(IllegalArgumentException.class, () -> new CaseExpression<String>(List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new CoalesceExpression<>(List.of(table.name())));
    assertThrows(
        IllegalArgumentException.class,
        () -> table.name().eq(LiteralExpression.nullLiteral(String.class)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            table
                .name()
                .eq(
                    new CastExpression<>(
                        LiteralExpression.nullLiteral(String.class), String.class)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArithmeticExpression<>(
                new ParameterSlot<>(0, BigInteger.class, false),
                ArithmeticOperator.DIVIDE,
                new ParameterSlot<>(1, BigInteger.class, false)));
    assertEquals(
        BigDecimal.class,
        new ArithmeticExpression<>(
                new ParameterSlot<>(0, BigDecimal.class, false),
                ArithmeticOperator.DIVIDE,
                new ParameterSlot<>(1, BigDecimal.class, false))
            .javaType());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArithmeticExpression<>(
                table.id(),
                ArithmeticOperator.ADD,
                new ParameterSlot<>(0, Long.class, SqlType.INTEGER, Nullability.NON_NULL)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CastExpression<>(LiteralExpression.trueLiteral(), Long.class));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConcatExpression(
                List.of(
                    table.name(),
                    new ParameterSlot<>(0, String.class, SqlType.BIGINT, Nullability.NON_NULL))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CaseExpression<>(
                List.of(new CaseWhen<>(table.id().isNotNull(), table.name())),
                new ParameterSlot<>(0, String.class, SqlType.BIGINT, Nullability.NON_NULL)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CoalesceExpression<>(
                List.of(
                    table.name(),
                    new ParameterSlot<>(0, String.class, SqlType.BIGINT, Nullability.NON_NULL))));
    assertThrows(IllegalArgumentException.class, () -> LiteralExpression.nullLiteral(long.class));
    assertThrows(IllegalArgumentException.class, () -> LiteralExpression.zero(String.class));
  }

  @Test
  void semanticValidatorRejectsNestedAndMutationReferencesOutsideTheirScope() {
    PetTable table = new PetTable();
    PetTable other =
        new PetTable(
            EntityMeta.simple(
                Pet.class,
                TableMeta.of("other_pet"),
                List.of(ID, NAME),
                new PrimaryKeyMeta<>(List.of(ID)),
                false));
    PetTable readOnly =
        new PetTable(
            EntityMeta.simple(
                Pet.class,
                TableMeta.of("pet_archive"),
                List.of(ID, NAME),
                new PrimaryKeyMeta<>(List.of(ID)),
                true));
    ParameterSlot<Long> id = new ParameterSlot<>(0, Long.class, false);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SelectStatement(
                List.of(
                    new CaseExpression<>(
                        List.of(new CaseWhen<>(table.id().eq(id), other.name())), table.name())),
                table));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SelectStatement(
                List.of(table.id()), table, new InPredicate<>(other.id(), List.of(), false)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InsertStatement(table, List.of(table.id()), List.of(other.id())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new UpdateStatement(
                table,
                List.of(new UpdateAssignment<>(table.name(), table.name())),
                other.id().eq(id)));
    assertThrows(
        IllegalArgumentException.class, () -> new DeleteStatement(table, other.id().eq(id)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InsertStatement(readOnly, List.of(readOnly.id()), List.of(id)));
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
  void selectPaginationOrderingHiddenItemsAndCountParticipateInStructure() {
    PetTable table = new PetTable();
    ParameterSlot<Integer> limit = new ParameterSlot<>(0, Integer.class, false);
    ParameterSlot<Long> offset = new ParameterSlot<>(1, Long.class, false);
    SelectStatement page =
        new SelectStatement(
            true,
            List.of(table.name()),
            List.of(new HiddenSelection(table.id(), Identifier.of("__skis_order_0"))),
            table,
            null,
            List.of(new OrderByItem(table.id(), OrderDirection.DESC, NullOrder.LAST)),
            new OffsetLimit(limit, offset));

    assertNotEquals(
        page,
        new SelectStatement(
            false,
            page.selections(),
            page.hiddenSelections(),
            table,
            null,
            page.orderBy(),
            page.pagination().orElseThrow()));
    assertNotEquals(
        page,
        new SelectStatement(
            true,
            page.selections(),
            List.of(),
            table,
            null,
            page.orderBy(),
            page.pagination().orElseThrow()));
    assertNotEquals(
        page,
        new SelectStatement(
            true,
            page.selections(),
            page.hiddenSelections(),
            table,
            null,
            List.of(new OrderByItem(table.id(), OrderDirection.ASC, NullOrder.LAST)),
            page.pagination().orElseThrow()));
    assertNotEquals(new CountAst(table, null, null), new CountAst(table, null, table.name()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SelectStatement(
                false,
                List.of(table.id()),
                List.of(),
                table,
                null,
                List.of(),
                new OffsetLimit(limit, offset)));
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
        IllegalArgumentException.class, () -> new DeleteStatement(table, table.id().eq(gappedId)));
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
