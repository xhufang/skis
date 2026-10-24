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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AggregateSemanticsTest {

  private static final List<ValueType> ORDERED_INPUTS =
      List.of(
          new ValueType(Byte.class, SqlType.TINYINT),
          new ValueType(Short.class, SqlType.SMALLINT),
          new ValueType(Integer.class, SqlType.INTEGER),
          new ValueType(Long.class, SqlType.BIGINT),
          new ValueType(BigInteger.class, SqlType.DECIMAL),
          new ValueType(BigDecimal.class, SqlType.DECIMAL),
          new ValueType(Float.class, SqlType.REAL),
          new ValueType(Double.class, SqlType.DOUBLE),
          new ValueType(Character.class, SqlType.CHARACTER),
          new ValueType(String.class, SqlType.VARCHAR),
          new ValueType(LocalDate.class, SqlType.DATE),
          new ValueType(java.sql.Date.class, SqlType.DATE),
          new ValueType(LocalTime.class, SqlType.TIME),
          new ValueType(java.sql.Time.class, SqlType.TIME),
          new ValueType(OffsetTime.class, SqlType.TIME_WITH_TIME_ZONE),
          new ValueType(LocalDateTime.class, SqlType.TIMESTAMP),
          new ValueType(java.sql.Timestamp.class, SqlType.TIMESTAMP),
          new ValueType(Instant.class, SqlType.TIMESTAMP_WITH_TIME_ZONE),
          new ValueType(OffsetDateTime.class, SqlType.TIMESTAMP_WITH_TIME_ZONE));

  private static final List<ValueType> DISTINCT_ONLY_INPUTS =
      List.of(
          new ValueType(Boolean.class, SqlType.BOOLEAN),
          new ValueType(UUID.class, SqlType.UUID),
          new ValueType(byte[].class, SqlType.VARBINARY));

  private static final List<NumericType> NUMERIC_INPUTS =
      List.of(
          new NumericType(Byte.class, Long.class, BigDecimal.class),
          new NumericType(Short.class, Long.class, BigDecimal.class),
          new NumericType(Integer.class, Long.class, BigDecimal.class),
          new NumericType(Long.class, BigInteger.class, BigDecimal.class),
          new NumericType(BigInteger.class, BigInteger.class, BigDecimal.class),
          new NumericType(BigDecimal.class, BigDecimal.class, BigDecimal.class),
          new NumericType(Float.class, Double.class, Double.class),
          new NumericType(Double.class, Double.class, Double.class));

  private static final List<NumericFactory> NUMERIC_FACTORIES =
      List.of(
          new NumericFactory(
              AggregateExpression.Kind.SUM, Long.class, SqlType.BIGINT, AggregateExpression::sumAsLong),
          new NumericFactory(
              AggregateExpression.Kind.SUM,
              BigInteger.class,
              SqlType.DECIMAL,
              AggregateExpression::sumAsBigInteger),
          new NumericFactory(
              AggregateExpression.Kind.SUM,
              BigDecimal.class,
              SqlType.DECIMAL,
              AggregateExpression::sumAsBigDecimal),
          new NumericFactory(
              AggregateExpression.Kind.SUM,
              Double.class,
              SqlType.DOUBLE,
              AggregateExpression::sumAsDouble),
          new NumericFactory(
              AggregateExpression.Kind.AVG,
              BigDecimal.class,
              SqlType.DECIMAL,
              AggregateExpression::avgAsBigDecimal),
          new NumericFactory(
              AggregateExpression.Kind.AVG,
              Double.class,
              SqlType.DOUBLE,
              AggregateExpression::avgAsDouble));

  @Test
  void countStarHasNoOperandAndCountNeverInheritsInputNullability() {
    AggregateExpression<Long> all = AggregateExpression.countAll();
    assertDescriptor(all, Long.class, SqlType.BIGINT, Nullability.NON_NULL);
    assertEquals(AggregateExpression.Kind.COUNT, all.kind());
    assertFalse(all.distinct());
    assertTrue(all.operand().isEmpty());

    for (Nullability nullability : Nullability.values()) {
      ParameterSlot<Integer> input = new ParameterSlot<>(7, Integer.class, nullability);
      AggregateExpression<Long> count = AggregateExpression.count(input);
      AggregateExpression<Long> distinct = AggregateExpression.countDistinct(input);
      assertDescriptor(count, Long.class, SqlType.BIGINT, Nullability.NON_NULL);
      assertDescriptor(distinct, Long.class, SqlType.BIGINT, Nullability.NON_NULL);
      assertSame(input, count.operand().orElseThrow());
      assertSame(input, distinct.operand().orElseThrow());
      assertFalse(count.distinct());
      assertTrue(distinct.distinct());
      assertNotEquals(all, count);
      assertNotEquals(count, distinct);
    }
    assertDescriptor(
        AggregateExpression.count(LiteralExpression.nullLiteral(Integer.class)),
        Long.class,
        SqlType.BIGINT,
        Nullability.NON_NULL);
  }

  @Test
  void allNumericInputsHaveFixedResultsAndRejectEveryWrongNumericFactory() {
    for (NumericType input : NUMERIC_INPUTS) {
      for (Nullability nullability : Nullability.values()) {
        ParameterSlot<? extends Number> slot = new ParameterSlot<>(7, input.javaType(), nullability);
        for (NumericFactory factory : NUMERIC_FACTORIES) {
          Class<?> expected =
              factory.kind() == AggregateExpression.Kind.SUM ? input.sumType() : input.averageType();
          if (factory.javaType() == expected) {
            AggregateExpression<?> result = factory.create().apply(slot);
            assertDescriptor(result, expected, factory.sqlType(), Nullability.NULLABLE);
            assertEquals(factory.kind(), result.kind());
            assertFalse(result.distinct());
            assertSame(slot, result.operand().orElseThrow());
          } else {
            assertThrows(IllegalArgumentException.class, () -> factory.create().apply(slot));
          }
        }
      }
    }
  }

  @Test
  void minMaxAndDistinctAcceptEveryExplicitJavaSqlPair() {
    for (ValueType input : ORDERED_INPUTS) {
      for (Nullability nullability : Nullability.values()) {
        ParameterSlot<?> slot = new ParameterSlot<>(0, input.javaType(), input.sqlType(), nullability);
        AggregateExpression<?> min = AggregateExpression.min(slot);
        AggregateExpression<?> max = AggregateExpression.max(slot);
        assertDescriptor(min, input.javaType(), input.sqlType(), Nullability.NULLABLE);
        assertDescriptor(max, input.javaType(), input.sqlType(), Nullability.NULLABLE);
        assertEquals(AggregateExpression.Kind.MIN, min.kind());
        assertEquals(AggregateExpression.Kind.MAX, max.kind());
        assertSame(slot, min.operand().orElseThrow());
        assertSame(slot, max.operand().orElseThrow());
        assertFalse(min.distinct());
        assertFalse(max.distinct());
        assertDescriptor(
            AggregateExpression.count(slot), Long.class, SqlType.BIGINT, Nullability.NON_NULL);
        assertDescriptor(
            AggregateExpression.countDistinct(slot), Long.class, SqlType.BIGINT, Nullability.NON_NULL);
      }
    }
  }

  @Test
  void booleanUuidAndBinaryAreCountableButNotMinMaxInputs() {
    for (ValueType input : DISTINCT_ONLY_INPUTS) {
      for (Nullability nullability : Nullability.values()) {
        ParameterSlot<?> slot = new ParameterSlot<>(0, input.javaType(), input.sqlType(), nullability);
        assertDescriptor(
            AggregateExpression.count(slot), Long.class, SqlType.BIGINT, Nullability.NON_NULL);
        assertDescriptor(
            AggregateExpression.countDistinct(slot), Long.class, SqlType.BIGINT, Nullability.NON_NULL);
        assertThrows(IllegalArgumentException.class, () -> AggregateExpression.min(slot));
        assertThrows(IllegalArgumentException.class, () -> AggregateExpression.max(slot));
      }
    }
  }

  @Test
  void ordinaryCountAcceptsOtherWhileRestrictedAggregatesRejectUnknownRepresentations() {
    for (Class<?> javaType :
        List.of(Object.class, Number.class, AtomicInteger.class, java.util.Date.class)) {
      ParameterSlot<?> slot = new ParameterSlot<>(0, javaType, SqlType.OTHER, Nullability.NULLABLE);
      assertDescriptor(
          AggregateExpression.count(slot), Long.class, SqlType.BIGINT, Nullability.NON_NULL);
      assertThrows(IllegalArgumentException.class, () -> AggregateExpression.countDistinct(slot));
      assertThrows(IllegalArgumentException.class, () -> AggregateExpression.min(slot));
      assertThrows(IllegalArgumentException.class, () -> AggregateExpression.max(slot));
    }
    for (NumericFactory factory : NUMERIC_FACTORIES) {
      assertThrows(
          IllegalArgumentException.class,
          () -> factory.create().apply(new ParameterSlot<>(0, AtomicInteger.class, true)));
      assertThrows(
          IllegalArgumentException.class,
          () -> factory.create().apply(new ParameterSlot<>(0, Number.class, true)));
    }
  }

  @Test
  void restrictedInputsRequireExactSqlTypesEvenWithinTheSameTypeFamily() {
    for (List<ValueType> inputs : List.of(ORDERED_INPUTS, DISTINCT_ONLY_INPUTS)) {
      for (ValueType input : inputs) {
        for (SqlType sqlType : SqlType.values()) {
          if (sqlType == input.sqlType()) {
            continue;
          }
          ParameterSlot<?> slot =
              new ParameterSlot<>(0, input.javaType(), sqlType, Nullability.NON_NULL);
          assertThrows(IllegalArgumentException.class, () -> AggregateExpression.countDistinct(slot));
          assertThrows(IllegalArgumentException.class, () -> AggregateExpression.min(slot));
          assertThrows(IllegalArgumentException.class, () -> AggregateExpression.max(slot));
        }
      }
    }
    for (NumericType input : NUMERIC_INPUTS) {
      for (SqlType sqlType : SqlType.values()) {
        if (sqlType == SqlType.fromJavaType(input.javaType())) {
          continue;
        }
        ParameterSlot<? extends Number> slot =
            new ParameterSlot<>(0, input.javaType(), sqlType, Nullability.NON_NULL);
        for (NumericFactory factory : NUMERIC_FACTORIES) {
          assertThrows(IllegalArgumentException.class, () -> factory.create().apply(slot));
        }
      }
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AggregateExpression.count(
                new ParameterSlot<>(0, String.class, SqlType.BIGINT, Nullability.NON_NULL)));
  }

  @Test
  void typedSqlNullRemainsAValueOperandAndNeverBecomesCountStar() {
    for (NumericType input : NUMERIC_INPUTS) {
      for (NumericFactory factory : NUMERIC_FACTORIES) {
        Class<?> expected =
            factory.kind() == AggregateExpression.Kind.SUM ? input.sumType() : input.averageType();
        if (factory.javaType() == expected) {
          assertDescriptor(
              factory.create().apply(LiteralExpression.nullLiteral(input.javaType())),
              expected,
              factory.sqlType(),
              Nullability.NULLABLE);
        }
      }
    }
    for (ValueType input : ORDERED_INPUTS) {
      LiteralExpression<?> sqlNull = LiteralExpression.nullLiteral(input.javaType());
      assertTrue(AggregateExpression.count(sqlNull).operand().isPresent());
      assertDescriptor(
          AggregateExpression.countDistinct(sqlNull), Long.class, SqlType.BIGINT, Nullability.NON_NULL);
      assertDescriptor(
          AggregateExpression.min(sqlNull), input.javaType(), input.sqlType(), Nullability.NULLABLE);
      assertDescriptor(
          AggregateExpression.max(sqlNull), input.javaType(), input.sqlType(), Nullability.NULLABLE);
    }
  }

  @Test
  void primitiveInputsProduceBoxedResultsWithTheSameNullableContract() {
    for (Class<?> primitive :
        List.of(byte.class, short.class, int.class, long.class, float.class, double.class, char.class)) {
      ParameterSlot<?> slot = new ParameterSlot<>(0, primitive, false);
      assertDescriptor(
          AggregateExpression.min(slot), slot.javaType(), slot.sqlType(), Nullability.NULLABLE);
      assertDescriptor(
          AggregateExpression.max(slot), slot.javaType(), slot.sqlType(), Nullability.NULLABLE);
      assertDescriptor(
          AggregateExpression.countDistinct(slot), Long.class, SqlType.BIGINT, Nullability.NON_NULL);
    }
    assertDescriptor(
        AggregateExpression.sumAsLong(new ParameterSlot<>(0, int.class, false)),
        Long.class,
        SqlType.BIGINT,
        Nullability.NULLABLE);
    assertDescriptor(
        AggregateExpression.countDistinct(new ParameterSlot<>(0, boolean.class, false)),
        Long.class,
        SqlType.BIGINT,
        Nullability.NON_NULL);
  }

  @Test
  void nullJavaArgumentsAreRejectedByEveryOperandFactory() {
    assertThrows(NullPointerException.class, () -> AggregateExpression.count(null));
    assertThrows(NullPointerException.class, () -> AggregateExpression.countDistinct(null));
    assertThrows(NullPointerException.class, () -> AggregateExpression.min(null));
    assertThrows(NullPointerException.class, () -> AggregateExpression.max(null));
    for (NumericFactory factory : NUMERIC_FACTORIES) {
      assertThrows(NullPointerException.class, () -> factory.create().apply(null));
    }
  }

  @Test
  void typeRulesRejectUnsupportedArityDistinctAndResultDescriptors() {
    ParameterSlot<Integer> input = new ParameterSlot<>(0, Integer.class, false);
    assertThrows(
        IllegalArgumentException.class,
        () -> AggregateTypeRules.validate(AggregateExpression.Kind.COUNT, true, null, Long.class));
    for (AggregateExpression.Kind kind : AggregateExpression.Kind.values()) {
      if (kind != AggregateExpression.Kind.COUNT) {
        assertThrows(
            IllegalArgumentException.class,
            () -> AggregateTypeRules.validate(kind, false, null, Long.class));
        assertThrows(
            IllegalArgumentException.class,
            () -> AggregateTypeRules.validate(kind, true, input, Long.class));
      }
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> AggregateTypeRules.validate(AggregateExpression.Kind.COUNT, false, input, Number.class));
    assertThrows(
        NullPointerException.class,
        () -> AggregateTypeRules.validate(null, false, input, Long.class));
    assertThrows(
        NullPointerException.class,
        () -> AggregateTypeRules.validate(AggregateExpression.Kind.COUNT, false, input, null));
    for (Class<?> javaType : List.of(String.class, Boolean.class)) {
      ParameterSlot<?> nonNumeric = new ParameterSlot<>(0, javaType, false);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              AggregateTypeRules.validate(
                  AggregateExpression.Kind.SUM, false, nonNumeric, Long.class));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              AggregateTypeRules.validate(
                  AggregateExpression.Kind.AVG, false, nonNumeric, BigDecimal.class));
    }
  }

  @Test
  void unknownNodesCannotEnterAggregatesDirectlyOrThroughStandardWrappers() {
    SqlExpression<Integer> opaque = new OpaqueExpression<>(Integer.class, SqlType.INTEGER);
    assertThrows(IllegalArgumentException.class, () -> AggregateExpression.count(opaque));
    assertThrows(IllegalArgumentException.class, () -> AggregateExpression.countDistinct(opaque));
    assertThrows(IllegalArgumentException.class, () -> AggregateExpression.min(opaque));
    assertThrows(IllegalArgumentException.class, () -> AggregateExpression.max(opaque));
    for (NumericFactory factory : NUMERIC_FACTORIES) {
      assertThrows(IllegalArgumentException.class, () -> factory.create().apply(opaque));
    }
    CastExpression<Long> cast = new CastExpression<>(opaque, Long.class);
    ArithmeticExpression<Integer> arithmetic =
        new ArithmeticExpression<>(
            opaque, ArithmeticOperator.ADD, LiteralExpression.one(Integer.class));
    assertThrows(IllegalArgumentException.class, () -> AggregateExpression.count(cast));
    assertThrows(IllegalArgumentException.class, () -> AggregateExpression.sumAsLong(arithmetic));
  }

  @Test
  void aggregateArgumentsRetainParameterConsistencyWithoutRequiringDenseOrdinals() {
    ParameterSlot<Integer> first = new ParameterSlot<>(7, Integer.class, false);
    ParameterSlot<Integer> repeated = new ParameterSlot<>(7, Integer.class, false);
    ArithmeticExpression<Integer> valid =
        new ArithmeticExpression<>(first, ArithmeticOperator.ADD, repeated);
    assertSame(valid, AggregateExpression.sumAsLong(valid).operand().orElseThrow());

    CaseExpression<Integer> validCase =
        new CaseExpression<>(
            List.of(new CaseWhen<>(new NullPredicate(repeated, NullOperator.IS_NULL), first)),
            LiteralExpression.zero(Integer.class));
    assertSame(validCase, AggregateExpression.sumAsLong(validCase).operand().orElseThrow());

    ArithmeticExpression<Integer> conflict =
        new ArithmeticExpression<>(
            first, ArithmeticOperator.ADD, new ParameterSlot<>(7, Integer.class, true));
    assertThrows(IllegalArgumentException.class, () -> AggregateExpression.count(conflict));
    assertThrows(IllegalArgumentException.class, () -> AggregateExpression.sumAsLong(conflict));
  }

  @Test
  void aggregateArgumentsRejectParameterJavaTypeConflictsAcrossCaseConditionAndResult() {
    ParameterSlot<Integer> value = new ParameterSlot<>(7, Integer.class, false);
    ParameterSlot<Long> conditionValue = new ParameterSlot<>(7, Long.class, false);
    CaseExpression<Integer> conflict =
        new CaseExpression<>(
            List.of(new CaseWhen<>(new NullPredicate(conditionValue, NullOperator.IS_NULL), value)),
            LiteralExpression.zero(Integer.class));

    IllegalArgumentException countFailure =
        assertThrows(IllegalArgumentException.class, () -> AggregateExpression.count(conflict));
    assertTrue(countFailure.getMessage().contains("parameter ordinal 7"));
    IllegalArgumentException sumFailure =
        assertThrows(IllegalArgumentException.class, () -> AggregateExpression.sumAsLong(conflict));
    assertTrue(sumFailure.getMessage().contains("parameter ordinal 7"));
  }

  @Test
  void aggregateArgumentsRejectParameterSqlTypeConflictsDirectlyAndInsideCase() {
    ParameterSlot<Integer> first = new ParameterSlot<>(7, Integer.class, false);
    // Both descriptors are locally compatible; reusing their ordinal is the only conflict.
    ParameterSlot<Integer> repeated =
        new ParameterSlot<>(7, Integer.class, SqlType.BIGINT, Nullability.NON_NULL);
    SqlPredicate conflict = new ComparisonPredicate<>(first, ComparisonOperator.EQUAL, repeated);
    IllegalArgumentException countFailure =
        assertThrows(IllegalArgumentException.class, () -> AggregateExpression.count(conflict));
    assertTrue(countFailure.getMessage().contains("parameter ordinal 7"));

    CaseExpression<Integer> wrappedConflict =
        new CaseExpression<>(
            List.of(new CaseWhen<>(conflict, LiteralExpression.one(Integer.class))),
            LiteralExpression.zero(Integer.class));
    IllegalArgumentException sumFailure =
        assertThrows(
            IllegalArgumentException.class, () -> AggregateExpression.sumAsLong(wrappedConflict));
    assertTrue(sumFailure.getMessage().contains("parameter ordinal 7"));
  }

  @Test
  void equalityAndHashingIncludeOperationDistinctInputAndItsDescriptor() {
    ParameterSlot<Integer> input = new ParameterSlot<>(0, Integer.class, false);
    AggregateExpression<Long> sum = AggregateExpression.sumAsLong(input);
    AggregateExpression<Long> equivalent =
        AggregateExpression.sumAsLong(new ParameterSlot<>(0, int.class, false));
    assertEquals(sum, sum);
    assertEquals(sum, equivalent);
    assertEquals(sum.hashCode(), equivalent.hashCode());
    assertEquals(AggregateExpression.countAll(), AggregateExpression.countAll());
    assertEquals(AggregateExpression.countAll().hashCode(), AggregateExpression.countAll().hashCode());
    assertNotEquals(sum, AggregateExpression.count(input));
    assertNotEquals(sum, AggregateExpression.sumAsLong(new ParameterSlot<>(1, Integer.class, false)));
    assertNotEquals(sum, AggregateExpression.sumAsLong(new ParameterSlot<>(0, Short.class, false)));
    assertNotEquals(sum, AggregateExpression.sumAsLong(new ParameterSlot<>(0, Integer.class, true)));
    assertNotEquals(AggregateExpression.min(input), AggregateExpression.max(input));
    assertNotEquals(AggregateExpression.count(input), AggregateExpression.countDistinct(input));
    assertNotEquals(sum, null);
    assertNotEquals(sum, input);
  }

  @Test
  void standardCompositionPreservesAggregateNullability() {
    AggregateExpression<Long> sum =
        AggregateExpression.sumAsLong(new ParameterSlot<>(0, Integer.class, false));
    AggregateExpression<Long> count = AggregateExpression.countAll();
    assertDescriptor(
        new ArithmeticExpression<>(sum, ArithmeticOperator.ADD, count),
        Long.class,
        SqlType.BIGINT,
        Nullability.NULLABLE);
    assertDescriptor(
        new CoalesceExpression<>(List.of(sum, LiteralExpression.zero(Long.class))),
        Long.class,
        SqlType.BIGINT,
        Nullability.NON_NULL);
    assertDescriptor(
        new CastExpression<>(sum, BigDecimal.class),
        BigDecimal.class,
        SqlType.DECIMAL,
        Nullability.NULLABLE);
    assertFalse(new NullPredicate(sum, NullOperator.IS_NULL).nullable());
    // Ownership of a nested aggregate needs the complete query-block context, not construction order.
    assertTrue(AggregateExpression.sumAsBigInteger(count).nullable());
  }

  @Test
  void recognizedScalarInputsRemainUsableWithoutRuntimeMappingDependencies() {
    TestTable table = new TestTable();
    SqlExpression<Integer> scalar =
        new ScalarSubqueryExpression<>(new SelectStatement(List.of(table.id), table));
    assertDescriptor(
        AggregateExpression.sumAsLong(scalar), Long.class, SqlType.BIGINT, Nullability.NULLABLE);
    assertDescriptor(
        AggregateExpression.min(scalar), Integer.class, SqlType.INTEGER, Nullability.NULLABLE);
    assertDescriptor(
        AggregateExpression.count(scalar), Long.class, SqlType.BIGINT, Nullability.NON_NULL);
    assertDescriptor(
        AggregateExpression.countDistinct(scalar), Long.class, SqlType.BIGINT, Nullability.NON_NULL);

    SqlExpression<Boolean> comparison =
        new ComparisonPredicate<>(
            table.id, ComparisonOperator.EQUAL, new ParameterSlot<>(7, Integer.class, true));
    assertDescriptor(
        AggregateExpression.count(comparison), Long.class, SqlType.BIGINT, Nullability.NON_NULL);
    assertDescriptor(
        AggregateExpression.countDistinct(comparison), Long.class, SqlType.BIGINT, Nullability.NON_NULL);
    assertThrows(IllegalArgumentException.class, () -> AggregateExpression.min(comparison));
  }

  @Test
  void concreteColumnsInterfaceVariablesGenericHelpersAndMethodReferencesRetainFixedTypes() {
    TestTable table = new TestTable();
    SqlExpression<Integer> interfaceInput = table.id;
    Function<SqlExpression<Integer>, AggregateExpression<Long>> sumFactory =
        AggregateExpression::sumAsLong;
    AggregateExpression<Long> direct = AggregateExpression.sumAsLong(table.id);
    assertEquals(direct, AggregateExpression.sumAsLong(interfaceInput));
    assertEquals(direct, sumIntegers(table.id));
    assertEquals(direct, sumFactory.apply(interfaceInput));
    assertSame(table.id, direct.operand().orElseThrow());
  }

  @Test
  void completeAnalysisAcceptsSelectAggregatesButMutationsStayClosed() {
    TestTable table = new TestTable();
    AggregateExpression<Long> aggregate = AggregateExpression.sumAsLong(table.id);
    SelectStatement statement = new SelectStatement(List.of(aggregate), table);
    assertSame(aggregate, statement.selections().getFirst());
    assertEquals(
        Nullability.NULLABLE,
        SemanticValidator.analyzeComplete(statement)
            .expressions()
            .getFirst()
            .effectiveNullability());
    ParameterSlot<Long> limit = new ParameterSlot<>(0, Long.class, false);
    SqlPredicate comparison =
        new ComparisonPredicate<>(aggregate, ComparisonOperator.GREATER_THAN, limit);
    assertThrows(IllegalArgumentException.class, () -> new DeleteStatement(table, comparison));

    ParameterSlot<Integer> conflicting = new ParameterSlot<>(0, Integer.class, true);
    SelectStatement original = new SelectStatement(List.of(aggregate), table);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SelectStatement(
                List.of(
                    AggregateExpression.sumAsLong(new ParameterSlot<>(0, Integer.class, false)),
                    conflicting),
                table));
    assertEquals(statement, original);
  }

  private static <N extends Number> AggregateExpression<Long> sumIntegers(SqlExpression<N> input) {
    return AggregateExpression.sumAsLong(input);
  }

  private static void assertDescriptor(
      SqlExpression<?> expression, Class<?> javaType, SqlType sqlType, Nullability nullability) {
    assertEquals(javaType, expression.javaType());
    assertEquals(sqlType, expression.sqlType());
    assertEquals(nullability, expression.nullability());
    assertEquals(nullability.isNullable(), expression.nullable());
  }

  private record ValueType(Class<?> javaType, SqlType sqlType) {}

  private record NumericType(
      Class<? extends Number> javaType, Class<?> sumType, Class<?> averageType) {}

  private record NumericFactory(
      AggregateExpression.Kind kind,
      Class<?> javaType,
      SqlType sqlType,
      Function<SqlExpression<? extends Number>, AggregateExpression<?>> create) {}

  private record OpaqueExpression<T>(Class<T> javaType, SqlType sqlType) implements SqlExpression<T> {
    @Override
    public Nullability nullability() {
      return Nullability.NON_NULL;
    }

    @Override
    public boolean nullable() {
      return false;
    }
  }

  private record Row(Integer id) {}

  private static final class TestTable extends TableExpression<Row> {
    private static final PropertyMeta<Row, Integer> ID =
        new PropertyMeta<>(0, "id", Integer.class, ColumnMeta.of("id", false));
    private static final EntityMeta<Row> META =
        EntityMeta.simple(
            Row.class,
            new TableMeta("", "", "aggregate_input"),
            List.of(ID),
            new PrimaryKeyMeta<>(List.of(ID)),
            false);

    private final ColumnExpression<Row, Integer> id = column(ID);

    private TestTable() {
      super(META);
    }

    private TestTable(Identifier alias) {
      super(META, alias);
    }

    @Override
    public TestTable as(String alias) {
      return new TestTable(Identifier.of(alias));
    }

    @Override
    public TestTable as(Identifier alias) {
      return new TestTable(alias);
    }
  }
}
