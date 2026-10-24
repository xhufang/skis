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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterRelationAstTest {

  @Test
  void aggregateNullabilityRemainsFixedThroughOuterJoinsAndStandardExpressions() {
    BaseTable left = new BaseTable("l");
    BaseTable right = new BaseTable("r");
    FromClause from = new FromClause(left,
        List.of(new JoinClause(JoinType.LEFT, right, left.id.eq(right.id))));
    var count = AggregateExpression.count(right.id);
    var sum = AggregateExpression.sumAsBigInteger(right.id);
    assertEquals(Nullability.NON_NULL, from.effectiveNullability(AggregateExpression.countAll()));
    assertEquals(Nullability.NON_NULL, from.effectiveNullability(count));
    assertEquals(Nullability.NULLABLE, from.effectiveNullability(sum));
    assertEquals(Nullability.NULLABLE,
        from.effectiveNullability(AggregateExpression.min(left.id)));
    assertEquals(Nullability.NULLABLE,
        from.effectiveNullability(new CastExpression<>(sum, BigDecimal.class)));
    assertEquals(Nullability.NON_NULL, from.effectiveNullability(
        new ArithmeticExpression<>(count, ArithmeticOperator.ADD, LiteralExpression.one(Long.class))));
    assertEquals(Nullability.NON_NULL, from.effectiveNullability(
        new CoalesceExpression<>(List.of(sum, LiteralExpression.zero(BigInteger.class)))));
  }

  @Test
  void parameterRowsHaveAnImmutableExactOutputShape() {
    ParameterSlot<Integer> parameter = new ParameterSlot<>(0, Integer.class, true);
    ArrayList<ParameterSlot<?>> slots = new ArrayList<>(List.of(parameter));
    ParameterRelationSource source = new ParameterRelationSource(slots, reference(parameter));
    slots.clear();
    assertEquals(List.of(parameter), source.parameters());
    assertSame(source.reference(), source.derivedReference().orElseThrow());
    assertTrue(source.entityTable().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> source.parameters().clear());
    assertThrows(IllegalArgumentException.class, () -> new ParameterRelationSource(List.of(), reference(parameter)));
    assertThrows(IllegalArgumentException.class, () -> new ParameterRelationSource(List.of(parameter),
        new DerivedRelationReference(Identifier.of("gp"), List.of(
            new DerivedOutputColumn(Identifier.of("p0"), Long.class, SqlType.BIGINT, Nullability.NULLABLE)))));
    assertThrows(IllegalArgumentException.class, () -> new ParameterRelationSource(List.of(parameter, parameter),
        new DerivedRelationReference(Identifier.of("gp"), List.of(
            new DerivedOutputColumn(Identifier.of("p0"), Integer.class, SqlType.INTEGER, Nullability.NULLABLE),
            new DerivedOutputColumn(Identifier.of("p1"), Integer.class, SqlType.INTEGER, Nullability.NULLABLE)))));
    var other = new ParameterSlot<>(0, Object.class, true);
    assertThrows(IllegalArgumentException.class, () -> new ParameterRelationSource(List.of(other), reference(other)));
  }

  @Test
  void parameterRelationsCannotBeNullExtendedOrExposeColumnsBeforeTheyExist() {
    BaseTable table = new BaseTable("b");
    ParameterSlot<Integer> parameter = new ParameterSlot<>(0, Integer.class, false);
    ParameterRelationSource source = new ParameterRelationSource(List.of(parameter), reference(parameter));
    assertThrows(IllegalArgumentException.class, () -> FromClause.of(source));
    assertThrows(IllegalArgumentException.class, () -> new FromClause(table,
        List.of(new JoinClause(JoinType.LEFT, source, new BooleanPredicate(true)))));
    assertThrows(IllegalArgumentException.class, () -> new FromClause(table, List.of(
        new JoinClause(JoinType.CROSS, source, null), new JoinClause(JoinType.CROSS, table.as(Identifier.of("later")), null))));
    BaseTable right = table.as(Identifier.of("r"));
    var gp = new DerivedColumnExpression<Integer>(source.reference(), 0);
    FromClause invalidScope = new FromClause(table, List.of(new JoinClause(JoinType.INNER, right,
        new ComparisonPredicate<>(gp, ComparisonOperator.EQUAL, LiteralExpression.one(Integer.class))),
        new JoinClause(JoinType.CROSS, source, null)));
    assertThrows(IllegalArgumentException.class,
        () -> SemanticValidator.analyzeComplete(new SelectStatement(List.of(table.id), invalidScope)));
  }

  @Test
  void typedParameterColumnsKeepLogicalDependenciesWithoutInventingRowIdentity() {
    BaseTable table = new BaseTable("b");
    var slot = new ParameterSlot<>(0, Integer.class, true);
    ParameterRelationSource source = new ParameterRelationSource(List.of(slot), reference(slot));
    FromClause from = new FromClause(table, List.of(new JoinClause(JoinType.CROSS, source, null)));
    var column = new DerivedColumnExpression<Integer>(source.reference(), 0);
    var analysis = SemanticValidator.analyzeComplete(new SelectStatement(List.of(column), from));
    ResolvedExpression selected = analysis.expressions().stream()
        .filter(expression -> expression.position().clause() == QueryClause.SELECT).findFirst().orElseThrow();
    assertTrue(selected.columnDependencies().isEmpty());
    assertEquals(1, selected.parameterDependencies().size());
    assertEquals(0, selected.parameterDependencies().getFirst().parameterOrdinal());
    assertEquals(Nullability.NULLABLE, selected.effectiveNullability());
    assertFalse(analysis.hasAggregateStructure());
    var gap = new ParameterSlot<>(1, Integer.class, true);
    ParameterRelationSource sparse = new ParameterRelationSource(List.of(gap), reference(gap));
    assertThrows(IllegalArgumentException.class, () -> SemanticValidator.analyzeComplete(new SelectStatement(List.of(table.id),
        new FromClause(table, List.of(new JoinClause(JoinType.CROSS, sparse, null))))));
  }

  @Test
  void decimalCastDetailsAreValidatedAndParticipateInResolvedIdentity() {
    BaseTable table = new BaseTable("b");
    var slot = new ParameterSlot<>(0, Byte.class, true);
    var small = new CastExpression<>(slot, BigDecimal.class, SqlType.DECIMAL, new DecimalPrecision(3, 0));
    var larger = new CastExpression<>(slot, BigDecimal.class, SqlType.DECIMAL, new DecimalPrecision(5, 0));
    assertNotEquals(small, larger);
    assertNotEquals(SemanticValidator.analyzeComplete(new SelectStatement(List.of(small), table)).structureKey(),
        SemanticValidator.analyzeComplete(new SelectStatement(List.of(larger), table)).structureKey());
    assertThrows(IllegalArgumentException.class, () -> new DecimalPrecision(0, 0));
    assertThrows(IllegalArgumentException.class, () -> new DecimalPrecision(1001, 0));
    assertThrows(IllegalArgumentException.class, () -> new DecimalPrecision(3, -1));
    assertThrows(IllegalArgumentException.class, () -> new DecimalPrecision(3, 4));
    assertThrows(IllegalArgumentException.class,
        () -> new CastExpression<>(slot, Long.class, SqlType.BIGINT, new DecimalPrecision(3, 0)));
  }

  @Test
  void rebuildingAggregatesCannotChangeTheirFixedResultType() {
    var real = new ParameterSlot<>(0, Float.class, true);
    var sum = AggregateExpression.sumAsDouble(real);
    assertSame(sum, sum.withOperand(real));
    assertEquals(Double.class, sum.withOperand(new CastExpression<>(real, Double.class)).javaType());
    assertThrows(IllegalArgumentException.class, () -> AggregateExpression.countAll().withOperand(real));
    var integer = new ParameterSlot<>(0, Integer.class, true);
    assertThrows(IllegalArgumentException.class,
        () -> AggregateExpression.sumAsLong(integer).withOperand(new CastExpression<>(integer, Long.class)));
  }

  private static DerivedRelationReference reference(ParameterSlot<?> slot) {
    return new DerivedRelationReference(Identifier.of("gp"), List.of(
        new DerivedOutputColumn(Identifier.of("p0"), slot.javaType(), slot.sqlType(), slot.nullability())));
  }

  private record Row() {}

  private static final class BaseTable extends TableExpression<Row> {
    private static final PropertyMeta<Row, Long> ID = new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
    private static final EntityMeta<Row> ENTITY = EntityMeta.simple(Row.class, TableMeta.of("base"), List.of(ID),
        new PrimaryKeyMeta<>(List.of(ID)), false);
    private final ColumnExpression<Row, Long> id = column(ID);
    private BaseTable(String alias) { super(ENTITY, Identifier.of(alias)); }
    @Override public BaseTable as(Identifier alias) { return new BaseTable(alias.value()); }
  }
}
