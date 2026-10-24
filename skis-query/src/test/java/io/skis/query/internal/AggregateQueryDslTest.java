package io.skis.query.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisErrorCode;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.NonNullSelectable;
import io.skis.query.QueryCondition;
import io.skis.query.QueryParameter;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnSelect;
import io.skis.query.Sql;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.LogicalOperator;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** T04 construction contracts; SQL evaluation, lowering and readers belong to T05/T06. */
class AggregateQueryDslTest {

  private static final SampleTable TABLE = new SampleTable();

  @Test
  void emptyMembershipPruningKeepsLocalAggregatesAndDropsTheirParameters() {
    var unused = Sql.parameter(Integer.class);
    var aggregate = Sql.sumAsLong(Sql.value(unused));
    var nestedAggregate = Sql.caseWhen(Sql.countAll().gt(0L), Sql.value(1L), Sql.value(2L));
    for (Selectable<Long> value : List.of(aggregate, nestedAggregate)) {
      var result = Sql.caseWhen(value.in(List.of()), Sql.value(1), Sql.value(0));
      ExpressionCapture capture = new ExpressionCapture();
      var selected = capture.selectable(result);
      CompiledQueryStructure structure =
          QueryModels.description(Sql.select(selected).from(TABLE)).state().structure();
      analyze(structure.validationStructure());
      var statement = new SelectStatement(structure.selections(), structure.fromClause());
      assertTrue(io.skis.sql.ast.SemanticValidator.analyzeComplete(statement).isGrouped());
      assertFalse(structure.parameterReferences().contains(unused));
      assertEquals(2, structure.parameterReferences().size());
    }
  }

  @Test
  void emptyMembershipFindsLocalAggregatesThroughEveryConditionAndOperandShape() {
    var count = Sql.countAll();
    var inner = TABLE.as("i");
    var plain = Sql.add(TABLE.id(), Sql.value(1));
    var constant = Sql.value(1).gt(0);
    List<QueryCondition> aggregated = List.of(count.gt(0L), count.gt(Sql.value(0L)),
        Sql.value(0L).lt(count), count.isNull(), count.between(0L, 1L),
        Sql.max(TABLE.label()).like("a%"), count.in(List.of(1L)),
        count.in(Sql.select(Sql.countAll()).from(inner)), constant.and(count.gt(0L)),
        count.gt(0L).or(constant), count.gt(0L).not());
    for (QueryCondition condition : aggregated) {
      assertTrue(groupedByEmptyMembership(Sql.caseWhen(condition, Sql.value(1L), Sql.value(2L))));
    }
    assertTrue(groupedByEmptyMembership(Sql.add(Sql.value(1L), count)));
    assertTrue(groupedByEmptyMembership(Sql.caseWhen(constant, count, Sql.value(0L))));
    List<QueryCondition> plainConditions = List.of(constant.and(constant), TABLE.id().isNull(),
        plain.gt(Sql.value(0)), Sql.value(0).lt(plain), Sql.exists(Sql.selectFrom(inner)));
    for (QueryCondition condition : plainConditions) {
      assertFalse(groupedByEmptyMembership(Sql.caseWhen(condition, Sql.value(1L), Sql.value(2L))));
    }
    assertFalse(groupedByEmptyMembership(Sql.add(plain, plain)));
    assertFalse(groupedByEmptyMembership(TABLE.id()));
  }

  private static <V> boolean groupedByEmptyMembership(Selectable<V> value) {
    var result = Sql.caseWhen(value.in(List.<V>of()), Sql.value(1), Sql.value(0));
    var selected = new ExpressionCapture().selectable(result);
    CompiledQueryStructure structure =
        QueryModels.description(Sql.select(selected).from(TABLE)).state().structure();
    analyze(structure.validationStructure());
    var statement = new SelectStatement(structure.selections(), structure.fromClause());
    return io.skis.sql.ast.SemanticValidator.analyzeComplete(statement).isGrouped();
  }

  @Test
  void aggregateFactoriesPreserveFixedResultsAndConservativeNullability() {
    assertResult(Sql.countAll(), Long.class, SqlType.BIGINT, false);
    assertResult(Sql.count(TABLE.label()), Long.class, SqlType.BIGINT, false);
    assertResult(Sql.countDistinct(TABLE.label()), Long.class, SqlType.BIGINT, false);
    assertResult(Sql.sumAsLong(Sql.value(1)), Long.class, SqlType.BIGINT, true);
    assertResult(Sql.sumAsLong(Sql.value((byte) 1)), Long.class, SqlType.BIGINT, true);
    assertResult(Sql.sumAsLong(Sql.value((short) 1)), Long.class, SqlType.BIGINT, true);
    assertResult(Sql.sumAsBigInteger(Sql.value(1L)), BigInteger.class, SqlType.DECIMAL, true);
    assertResult(Sql.sumAsBigInteger(Sql.value(BigInteger.ONE)), BigInteger.class, SqlType.DECIMAL, true);
    assertResult(Sql.sumAsBigDecimal(Sql.value(BigDecimal.ONE)), BigDecimal.class, SqlType.DECIMAL, true);
    assertResult(Sql.sumAsDouble(Sql.value(1F)), Double.class, SqlType.DOUBLE, true);
    assertResult(Sql.sumAsDouble(Sql.value(1D)), Double.class, SqlType.DOUBLE, true);
    assertResult(Sql.avgAsBigDecimal(Sql.value(1L)), BigDecimal.class, SqlType.DECIMAL, true);
    assertResult(Sql.avgAsDouble(Sql.value(1F)), Double.class, SqlType.DOUBLE, true);
    assertResult(Sql.min(TABLE.label()), String.class, SqlType.VARCHAR, true);
    assertResult(Sql.max(TABLE.id()), Integer.class, SqlType.INTEGER, true);
    assertTypeFailure(() -> Sql.sumAsLong(Sql.value(1L)));
    assertTypeFailure(() -> Sql.sumAsBigDecimal(Sql.value(1)));
    assertTypeFailure(() -> Sql.avgAsDouble(Sql.value(BigDecimal.ONE)));
    assertTypeFailure(() -> Sql.min(Sql.value(true)));
    assertTypeFailure(() -> Sql.max(Sql.value(UUID.randomUUID())));
    assertTypeFailure(() -> Sql.countDistinct(otherValue()));
    assertResult(Sql.count(otherValue()), Long.class, SqlType.BIGINT, false);
    assertEquals(AggregateExpression.Kind.COUNT,
        assertInstanceOf(AggregateExpression.class, compile(Sql.countAll()).expression()).kind());
    assertTrue(assertInstanceOf(AggregateExpression.class,
        compile(Sql.countDistinct(TABLE.label())).expression()).distinct());
  }

  @Test
  void allValueOverloadsUseTheSameExactBuiltinWhitelist() {
    List<Object> values = List.of(
        true, (byte) 1, (short) 2, 3, 4L, 5F, 6D, BigInteger.ONE, BigDecimal.ONE,
        'x', "text", new byte[] {1}, UUID.randomUUID(), LocalDate.of(2026, 1, 2),
        java.sql.Date.valueOf("2026-01-02"), LocalTime.NOON, java.sql.Time.valueOf("12:00:00"),
        OffsetTime.parse("12:00:00+08:00"), LocalDateTime.of(2026, 1, 2, 12, 0),
        Timestamp.valueOf("2026-01-02 12:00:00"), Instant.EPOCH,
        OffsetDateTime.parse("2026-01-02T12:00:00+08:00"));
    for (Object value : values) {
      Selectable<?> captured = Sql.value(value);
      assertResult(captured, value.getClass(), SqlType.fromJavaType(value.getClass()), false);
      assertValueOverloads(value.getClass(), value);
      if (!(value instanceof byte[])) {
        assertCastTarget(value);
      }
    }
    assertResult(Sql.value(int.class, 1), Integer.class, SqlType.INTEGER, false);
    assertResult(Sql.value(char.class, null), Character.class, SqlType.CHARACTER, true);
    for (Object value : List.of(new Object(), new AtomicInteger(1), new int[] {1},
        new String[] {"x"}, Thread.State.NEW, new CustomDecimal())) {
      assertTypeFailure(() -> Sql.value(value));
      assertTypeFailure(() -> assertValueOverloads(value.getClass(), value));
    }
    assertTypeFailure(() -> Sql.value(Sql.parameter(Number.class)));
    assertTypeFailure(() -> Sql.value(Number.class, 1));
    assertThrows(NullPointerException.class, () -> Sql.value((Object) null));
    assertThrows(NullPointerException.class, () -> Sql.value((QueryParameter<String>) null));
  }

  @Test
  void valuesAreSnapshottedOnceAndSharedAcrossCompositionAndCaptureBoundaries() {
    byte[] bytes = {1, 2};
    NonNullSelectable<byte[]> value = Sql.value(bytes);
    bytes[0] = 9;
    Selectable<Long> count = Sql.countDistinct(value);
    CompiledExpression first = captureAndCompile(count);
    CompiledExpression second = captureAndCompile(count);
    assertArrayEquals(new byte[] {1, 2}, (byte[]) first.arguments().getFirst());
    assertSame(first.arguments().getFirst(), second.arguments().getFirst());
    assertNotSame(bytes, first.arguments().getFirst());

    Timestamp timestamp = Timestamp.valueOf("2026-01-02 12:00:00.123456789");
    Timestamp expected = Timestamp.valueOf("2026-01-02 12:00:00.123456789");
    Selectable<Timestamp> time = Sql.value(timestamp);
    timestamp.setNanos(0);
    assertEquals(expected, captureAndCompile(time).arguments().getFirst());
    java.sql.Date date = java.sql.Date.valueOf("2026-01-02");
    Selectable<java.sql.Date> dateValue = Sql.value(date);
    date.setTime(0);
    assertEquals(java.sql.Date.valueOf("2026-01-02"), captureAndCompile(dateValue).arguments().getFirst());
    java.sql.Time clock = java.sql.Time.valueOf("12:34:56");
    Selectable<java.sql.Time> clockValue = Sql.value(clock);
    clock.setTime(0);
    assertEquals(java.sql.Time.valueOf("12:34:56"), captureAndCompile(clockValue).arguments().getFirst());
    assertFalse(ExpressionCapture.capturesValues(new ExpressionCapture().selectable(count)));
  }

  @Test
  void caseConditionsAndResultValuesShareOneEnvironmentInAstTraversalOrder() {
    Selectable<String> expression = Sql.caseWhen(TABLE.id().eq(5), Sql.value("yes"), Sql.value("no"));
    CompiledExpression compiled = captureAndCompile(expression);
    assertEquals(List.of(5, "yes", "no"), compiled.arguments());
    CaseExpression<?> ast = assertInstanceOf(CaseExpression.class, compiled.expression());
    assertEquals(1, assertInstanceOf(ParameterSlot.class, ast.branches().getFirst().result()).ordinal());
    assertEquals(2, assertInstanceOf(ParameterSlot.class, ast.otherwise().orElseThrow()).ordinal());
  }

  @Test
  void independentCaptureBoundariesShareCompilationAndKeepValuesOutsideTheStructure() {
    Selectable<Integer> expression = Sql.caseWhen(TABLE.id().ge(5),
        Sql.add(TABLE.id(), Sql.value(1)), Sql.value(0));
    ExpressionCapture selection = new ExpressionCapture();
    ExpressionCapture filtering = new ExpressionCapture();
    ExpressionCapture grouping = new ExpressionCapture();
    ExpressionCapture having = new ExpressionCapture();
    ExpressionCapture ordering = new ExpressionCapture();
    CompiledQueryStructure structure = QueryModels.description(
        Sql.select(selection.selectable(expression)).from(TABLE)
            .where(filtering.condition(expression.isNotNull()))
            .groupBy(grouping.selectable(expression))
            .having(having.condition(expression.isNotNull()))
            .orderBy(ordering.selectable(expression).asc())).state().structure();

    SqlExpression<?> selected = structure.selections().getFirst();
    assertSame(selected, assertInstanceOf(NullPredicate.class, structure.where()).operand());
    assertSame(selected, structure.groupBy().getFirst());
    assertSame(selected, assertInstanceOf(NullPredicate.class, structure.having()).operand());
    assertSame(selected, structure.orderBy().getFirst().expression());
    assertTrue(structure.parameters().isEmpty());
    QueryParameters parameters = selection.parameters().merge(filtering.parameters())
        .merge(grouping.parameters()).merge(having.parameters()).merge(ordering.parameters());
    assertEquals(List.of(5, 1, 0), structure.arguments(parameters));
    analyze(structure);
  }

  @Test
  void detachedCompilationMemoRemainsLocalToEachQueryBlock() {
    Selectable<Integer> expression = Sql.add(TABLE.id(), Sql.value(3));
    ExpressionCapture outerCapture = new ExpressionCapture();
    ExpressionCapture innerCapture = new ExpressionCapture();
    Selectable<Integer> outer = outerCapture.selectable(expression);
    Selectable<Integer> inner = innerCapture.selectable(expression);
    StatementParameterLayout layout = new StatementParameterLayout();
    QueryConditionCompiler compiler = new QueryConditionCompiler(layout, new QueryParameterBindings());
    ArithmeticExpression<?> outerAst = assertInstanceOf(ArithmeticExpression.class,
        compiler.expression(outer));
    SelectStatement child = compiler.subquery(QueryModels.description(
        Sql.select(inner).from(TABLE)).state());
    ArithmeticExpression<?> innerAst = assertInstanceOf(ArithmeticExpression.class,
        child.selections().getFirst());

    assertNotSame(outerAst, innerAst);
    assertEquals(0, assertInstanceOf(ParameterSlot.class, outerAst.right()).ordinal());
    assertEquals(1, assertInstanceOf(ParameterSlot.class, innerAst.right()).ordinal());
    assertSame(layout.parameterReferences().get(0), layout.parameterReferences().get(1));
    assertEquals(List.of(3, 3), outerCapture.parameters().merge(innerCapture.parameters())
        .valuesFor(layout.parameterReferences()));
  }

  @Test
  void independentDetachedValuesKeepSeparateLogicalSlotsEvenWithEqualDescriptors() {
    ExpressionCapture first = new ExpressionCapture();
    ExpressionCapture second = new ExpressionCapture();
    CompiledExpression compiled = compile(Sql.add(
        first.selectable(Sql.value(3)), second.selectable(Sql.value(3))));
    ArithmeticExpression<?> ast = assertInstanceOf(ArithmeticExpression.class, compiled.expression());

    assertEquals(2, compiled.references().size());
    assertNotSame(compiled.references().get(0), compiled.references().get(1));
    assertEquals(0, assertInstanceOf(ParameterSlot.class, ast.left()).ordinal());
    assertEquals(1, assertInstanceOf(ParameterSlot.class, ast.right()).ordinal());
    assertTrue(compiled.parameters().isEmpty());
    assertEquals(List.of(3, 3), first.parameters().merge(second.parameters())
        .valuesFor(compiled.references()));
  }

  @Test
  void detachedMemoDoesNotSuppressValueFragmentsInAnUncapturedPreview() {
    Selectable<Integer> expression = Sql.add(TABLE.id(), Sql.value(3));
    Selectable<Integer> detached = new ExpressionCapture().selectable(expression);
    StatementParameterLayout layout = new StatementParameterLayout();
    QueryParameterBindings bindings = new QueryParameterBindings();
    QueryConditionCompiler compiler = new QueryConditionCompiler(layout, bindings);
    compiler.expression(detached);
    compiler.expression(expression);
    assertEquals(List.of(3), bindings.parameters().valuesFor(layout.parameterReferences()));
  }

  @Test
  void capturesOfASharedSubqueryCompositeKeepTheirOwnOccurrences() {
    SampleTable childTable = TABLE.as("capture_child");
    QueryParameter<Integer> parameter = Sql.parameter(Integer.class, "nested");
    SingleColumnSelect<Integer> child = Sql.select(childTable.id()).from(childTable)
        .where(childTable.id().eq(parameter));
    Selectable<Integer> expression = Sql.add(Sql.scalar(child), Sql.value(1));
    ExpressionCapture selection = new ExpressionCapture();
    ExpressionCapture ordering = new ExpressionCapture();
    CompiledQueryStructure structure = QueryModels.description(
        Sql.select(selection.selectable(expression)).from(TABLE)
            .orderBy(ordering.selectable(expression).asc())).state().structure();

    assertNotSame(structure.selections().getFirst(), structure.orderBy().getFirst().expression());
    assertEquals(3, structure.parameterReferences().size());
    assertSame(parameter, structure.parameterReferences().get(0));
    assertSame(parameter, structure.parameterReferences().get(2));
    assertTrue(structure.parameters().isEmpty());
    assertEquals(List.of(7, 1, 7), structure.arguments(
        selection.parameters().merge(ordering.parameters()).bind(parameter, 7)));
    analyze(structure);
  }

  @Test
  void sharedCompositesKeepEmbeddedQueryOccurrencesAndSlotsIndependent() {
    QueryParameter<Integer> parameter = Sql.parameter(Integer.class, "nested");
    SingleColumnSelect<Integer> child = Sql.select(TABLE.id()).from(TABLE)
        .where(TABLE.id().eq(parameter));
    Selectable<Integer> expression = Sql.add(Sql.scalar(child), Sql.value(1));
    CompiledExpression compiled = compile(Sql.add(expression, expression));
    assertEquals(3, compiled.references().size());
    assertSame(parameter, compiled.references().get(0));
    assertSame(parameter, compiled.references().get(2));
    ArithmeticExpression<?> ast = assertInstanceOf(ArithmeticExpression.class, compiled.expression());
    assertNotSame(ast.left(), ast.right());
    Selectable<Integer> conditional = Sql.caseWhen(Sql.exists(child), Sql.value(1), Sql.value(2));
    CompiledExpression cases = compile(Sql.add(conditional, conditional));
    assertEquals(4, cases.references().size());
    assertSame(parameter, cases.references().get(0));
    assertSame(parameter, cases.references().get(3));
  }

  @Test
  void parameterViewsRetainIdentityAndIndependentValuesDoNotMerge() {
    QueryParameter<Integer> parameter = Sql.parameter(Integer.class, "same");
    Selectable<Integer> first = Sql.value(parameter);
    Selectable<Integer> second = Sql.value(parameter);
    assertEquals(SelectableSupport.expressionIdentity(first), SelectableSupport.expressionIdentity(second));
    CompiledExpression reused = compile(Sql.add(first, second));
    assertEquals(List.of(parameter), reused.references());
    ArithmeticExpression<?> sum = assertInstanceOf(ArithmeticExpression.class, reused.expression());
    assertSame(sum.left(), sum.right());
    assertNotEquals(SelectableSupport.expressionIdentity(first),
        SelectableSupport.expressionIdentity(Sql.value(Sql.parameter(Integer.class, "same"))));
    assertEquals(2, compile(Sql.add(Sql.value(1), Sql.value(1))).references().size());
    NonNullSelectable<Integer> captured = Sql.value(1);
    assertEquals(1, compile(Sql.add(captured, captured)).references().size());
  }

  @Test
  void parameterViewsShareMappedSlotsInEitherEncounterOrderWithoutBridgingOtherSources() {
    QueryParameter<Integer> parameter = Sql.parameter(Integer.class, "id");
    Selectable<Integer> view = Sql.value(parameter);
    QueryCondition column = TABLE.id().eq(parameter);
    for (QueryCondition condition : List.of(
        view.isNotNull().and(column), column.and(view.isNotNull()))) {
      CompiledQueryStructure structure = QueryModels.description(
          Sql.select(TABLE.id()).from(TABLE).where(condition)).state().structure();
      assertEquals(List.of(parameter), structure.parameterReferences());
      assertTrue(SelectableSupport.sameOccurrence(TABLE.id(), structure.parameterSources().getFirst()));
      assertEquals(1, structure.builtinParameterSources().size());
      analyze(structure);
    }
    CompiledQueryStructure selectedView = QueryModels.description(
        Sql.select(view).from(TABLE).where(column)).state().structure();
    assertEquals(List.of(parameter), selectedView.parameterReferences());
    assertTrue(SelectableSupport.sameOccurrence(TABLE.id(), selectedView.parameterSources().getFirst()));
    analyze(selectedView);

    SampleTable other = TABLE.as("other");
    QueryCondition conflicting = column.and(view.isNotNull()).and(other.id().eq(parameter));
    QueryValidationException failure = assertThrows(QueryValidationException.class,
        () -> QueryModels.description(Sql.select(TABLE.id()).from(TABLE).where(conflicting))
            .state().structure());
    assertEquals(SkisErrorCode.QUERY_PARAMETER_LAYOUT, failure.code());
    assertEquals(SkisDiagnosticStage.COMPILATION, failure.diagnostics().stage());
  }

  @Test
  void distinctCompositesReuseSelectedSubqueriesAfterValidatingEachOriginalOccurrence() {
    SampleTable childTable = TABLE.as("child");
    QueryParameter<Integer> parameter = Sql.parameter(Integer.class, "childId");
    Selectable<Integer> scalar = Sql.scalar(Sql.select(childTable.id()).from(childTable)
        .where(childTable.id().eq(parameter)));
    Selectable<Integer> offset = Sql.value(Sql.parameter(Integer.class, "offset"));
    for (Selectable<Integer> expression : List.of(
        Sql.cast(scalar, Integer.class, SqlType.INTEGER), Sql.add(scalar, offset))) {
      CompiledQueryStructure structure = QueryModels.description(
          Sql.select(expression).from(TABLE).distinct().orderBy(expression.asc()))
          .state().structure();
      assertNotSame(structure, structure.validationStructure());
      assertSame(structure.selections().getFirst(), structure.orderBy().getFirst().expression());
      assertEquals(structure.parameterReferences().size() + 1,
          structure.validationStructure().parameterReferences().size());
      analyze(structure.validationStructure());
      analyze(structure);
    }
  }

  @Test
  void pureDescriptionsRejectValuesAtEveryCaptureBoundary() {
    NonNullSelectable<String> value = Sql.value("sensitive-sentinel");
    assertCaptured(() -> Sql.select(value));
    assertCaptured(() -> Sql.selectNullable(value));
    assertCaptured(() -> Sql.output(value, "value"));
    assertCaptured(() -> Sql.outputNullable(value, "value"));
    SingleColumnSelect<Integer> base = Sql.select(TABLE.id()).from(TABLE);
    assertCaptured(() -> base.groupBy(value));
    assertCaptured(() -> base.where(value.eq(TABLE.label())));
    assertCaptured(() -> base.having(value.isNotNull()));
    assertCaptured(() -> base.orderBy(value.asc()));
    assertCaptured(() -> Sql.select(Sql.caseWhen(TABLE.id().eq(1),
        Sql.value(Sql.parameter(String.class)), Sql.value(Sql.parameter(String.class)))));
    assertCaptured(() -> Sql.select(Sql.value(String.class, null)));
    assertTrue(QueryModels.description(base).state().structure().parameters().isEmpty());
  }

  @Test
  void standardExpressionsValidateExactTargetsCompatibleResultsAndNullability() {
    NonNullSelectable<Integer> one = Sql.value(1);
    NonNullSelectable<Integer> two = Sql.value(2);
    assertResult(Sql.add(one, two), Integer.class, SqlType.INTEGER, false);
    assertResult(Sql.subtract(one, two), Integer.class, SqlType.INTEGER, false);
    assertResult(Sql.multiply(one, two), Integer.class, SqlType.INTEGER, false);
    assertResult(Sql.divide(one, two), Integer.class, SqlType.INTEGER, false);
    assertResult(Sql.add(one, Sql.value(Integer.class, null)), Integer.class, SqlType.INTEGER, true);
    assertResult(Sql.cast(one, Long.class, SqlType.BIGINT), Long.class, SqlType.BIGINT, false);
    assertResult(Sql.cast(one, BigInteger.class, SqlType.DECIMAL), BigInteger.class, SqlType.DECIMAL, false);
    assertResult(Sql.cast(one, BigDecimal.class, SqlType.DECIMAL), BigDecimal.class, SqlType.DECIMAL, false);
    assertTypeFailure(() -> Sql.cast(one, Integer.class, SqlType.BIGINT));
    assertTypeFailure(() -> Sql.cast(one, Long.class, SqlType.INTEGER));
    assertTypeFailure(() -> Sql.cast(one, byte[].class, SqlType.VARBINARY));
    assertTypeFailure(() -> Sql.cast(one, Object.class, SqlType.OTHER));
    assertTypeFailure(() -> Sql.cast(one, LocalDate.class, SqlType.DATE));
    assertResult(Sql.cast(one, int.class, SqlType.INTEGER), Integer.class, SqlType.INTEGER, false);
    assertTypeFailure(() -> Sql.divide(Sql.value(BigInteger.ONE), Sql.value(BigInteger.TWO)));
    assertResult(Sql.divide(Sql.value(1), Sql.value(0)), Integer.class, SqlType.INTEGER, false);
    assertResult(Sql.caseWhen(TABLE.id().isNotNull(), one, two), Integer.class, SqlType.INTEGER, false);
    assertResult(Sql.caseWhen(TABLE.id().isNotNull(), one, Sql.value(Integer.class, null)),
        Integer.class, SqlType.INTEGER, true);
    assertResult(Sql.coalesce(TABLE.label(), Sql.value("fallback")), String.class, SqlType.VARCHAR, false);
    assertResult(Sql.concat(Sql.value("a"), Sql.value("b")), String.class, SqlType.VARCHAR, false);
    assertResult(Sql.concat(TABLE.label(), Sql.value("b")), String.class, SqlType.VARCHAR, true);
    assertInstanceOf(CaseExpression.class, compile(Sql.caseWhen(TABLE.id().isNotNull(), one, two)).expression());
    assertTypeFailure(() -> Sql.coalesce());
    assertTypeFailure(() -> Sql.coalesce(one));
    assertTypeFailure(() -> Sql.concat());
    assertTypeFailure(() -> Sql.concat(Sql.value("a")));
    assertIncompatibleResultsRejected();
    Selectable<Number> integer = Sql.value((Number) 1);
    Selectable<Number> bigint = Sql.value((Number) 2L);
    assertTypeFailure(() -> Sql.caseWhen(TABLE.id().isNotNull(), integer, bigint));
    assertTypeFailure(() -> Sql.coalesce(integer, bigint));
    Selectable<String> characterSql = new ExpressionSelectable<>(
        new ParameterSlot<>(0, String.class, SqlType.CHARACTER, Nullability.NULLABLE));
    assertResult(Sql.coalesce(TABLE.label(), characterSql), String.class, SqlType.VARCHAR, true);
    assertResult(Sql.caseWhen(TABLE.id().isNotNull(), TABLE.label(), characterSql),
        String.class, SqlType.VARCHAR, true);
  }

  @Test
  void integerBoundaryValuesAreNeverEvaluatedWhileConstructingExpressions() {
    assertResult(Sql.divide(Sql.value(Byte.MIN_VALUE), Sql.value((byte) -1)), Byte.class, SqlType.TINYINT, false);
    assertResult(Sql.divide(Sql.value(Short.MIN_VALUE), Sql.value((short) -1)), Short.class, SqlType.SMALLINT, false);
    assertResult(Sql.divide(Sql.value(Integer.MIN_VALUE), Sql.value(-1)), Integer.class, SqlType.INTEGER, false);
    assertResult(Sql.divide(Sql.value(Long.MIN_VALUE), Sql.value(-1L)), Long.class, SqlType.BIGINT, false);
    assertResult(Sql.divide(Sql.value(-5), Sql.value(2)), Integer.class, SqlType.INTEGER, false);
    assertResult(Sql.divide(Sql.value(BigDecimal.ONE), Sql.value(BigDecimal.ZERO)),
        BigDecimal.class, SqlType.DECIMAL, false);
    assertResult(Sql.add(Sql.value(BigInteger.ONE), Sql.value(BigInteger.TWO)),
        BigInteger.class, SqlType.DECIMAL, false);
  }

  @Test
  void nullAndForeignOperandsFailBeforeTheyCanReachAQuery() {
    Selectable<Integer> foreign = new Selectable<>() {
      @Override public Class<Integer> javaType() { return Integer.class; }
      @Override public SqlType sqlType() { return SqlType.INTEGER; }
      @Override public Nullability nullability() { return Nullability.NON_NULL; }
    };
    assertEquals(SkisErrorCode.QUERY_UNSUPPORTED_STRUCTURE,
        assertThrows(QueryValidationException.class, () -> Sql.count(foreign)).code());
    assertEquals(SkisErrorCode.QUERY_UNSUPPORTED_STRUCTURE,
        assertThrows(QueryValidationException.class, () -> Sql.add(foreign, Sql.value(1))).code());
    assertThrows(NullPointerException.class, () -> Sql.count(null));
    assertThrows(NullPointerException.class, () -> Sql.sumAsLong(null));
    assertThrows(NullPointerException.class, () -> Sql.add((Selectable<Integer>) null, Sql.value(1)));
    assertThrows(NullPointerException.class, () -> Sql.caseWhen(null, Sql.value(1), Sql.value(2)));
    assertThrows(NullPointerException.class, () -> Sql.coalesce((Selectable<Integer>[]) null));
    assertThrows(NullPointerException.class, () -> Sql.concat((Selectable<String>[]) null));
  }

  @Test
  void groupingAndHavingAreImmutableIndependentAndLeftAssociative() {
    NonNullSelectable<Long> count = Sql.countAll();
    QueryParameter<Long> threshold = Sql.parameter(Long.class, "threshold");
    QueryParameter<Integer> id = Sql.parameter(Integer.class, "id");
    SingleColumnSelect<Long> base = Sql.select(count).from(TABLE);
    SingleColumnSelect<Long> grouped = base.having(count.ge(threshold))
        .orHaving(count.isNotNull()).andHaving(count.gt(threshold))
        .where(TABLE.id().eq(id)).and(TABLE.id().isNotNull()).groupBy(TABLE.id());
    CompiledQueryStructure structure = QueryModels.description(grouped).state().structure();
    assertEquals(1, structure.groupBy().size());
    assertEquals(List.of(id, threshold), structure.parameterReferences());
    LogicalPredicate having = assertInstanceOf(LogicalPredicate.class, structure.having());
    assertEquals(LogicalOperator.AND, having.operator());
    assertEquals(LogicalOperator.OR,
        assertInstanceOf(LogicalPredicate.class, having.operands().getFirst()).operator());
    assertEquals(LogicalOperator.AND, assertInstanceOf(LogicalPredicate.class, structure.where()).operator());
    assertNull(QueryModels.description(base).state().where());
    assertNull(QueryModels.description(base).state().having());
    assertTrue(QueryModels.description(base).state().groupBy().isEmpty());
    analyze(structure);
    assertEquals(Long.class, Sql.scalar(grouped).javaType());
    TABLE.id().in(Sql.select(TABLE.id()).from(TABLE).groupBy(TABLE.id()));
    analyze(QueryModels.description(base.having(count.isNotNull())).state().structure());
  }

  @Test
  void groupingConstructionErrorsAndResolvedSemanticErrorsKeepSeparateCodes() {
    SingleColumnSelect<Integer> base = Sql.select(TABLE.id()).from(TABLE);
    assertConstructionCode(SkisErrorCode.QUERY_GROUP_BY_CONTRACT, () -> base.groupBy());
    assertConstructionCode(SkisErrorCode.QUERY_GROUP_BY_CONTRACT, () -> base.groupBy((Selectable<?>[]) null));
    assertConstructionCode(SkisErrorCode.QUERY_GROUP_BY_CONTRACT, () -> base.groupBy((Selectable<?>) null));
    SingleColumnSelect<Integer> grouped = base.groupBy(TABLE.id());
    assertConstructionCode(SkisErrorCode.QUERY_GROUP_BY_CONTRACT, () -> grouped.groupBy(TABLE.id()));
    assertConstructionCode(SkisErrorCode.QUERY_HAVING_CONTRACT, () -> base.having(null));
    assertConstructionCode(SkisErrorCode.QUERY_HAVING_CONTRACT, () -> base.andHaving(TABLE.id().isNotNull()));
    assertConstructionCode(SkisErrorCode.QUERY_HAVING_CONTRACT, () -> base.orHaving(TABLE.id().isNotNull()));
    assertConstructionCode(SkisErrorCode.QUERY_HAVING_CONTRACT,
        () -> base.having(TABLE.id().isNotNull()).having(TABLE.id().isNull()));
    Selectable<?>[] keys = {TABLE.id()};
    SingleColumnSelect<Integer> copied = base.groupBy(keys);
    keys[0] = TABLE.label();
    analyze(QueryModels.description(copied).state().structure());
    assertEquals(SkisErrorCode.QUERY_GROUPING_CONTRACT,
        assertThrows(QueryValidationException.class, () -> analyze(QueryModels.description(
            base.groupBy(TABLE.id(), TABLE.id())).state().structure())).code());
    assertEquals(SkisErrorCode.QUERY_AGGREGATE_CONTRACT,
        assertThrows(QueryValidationException.class, () -> analyze(QueryModels.description(
            base.where(Sql.countAll().isNotNull())).state().structure())).code());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void assertValueOverloads(Class type, Object value) {
    assertResult(Sql.value(type, value), type, SqlType.fromJavaType(type), false);
    assertResult(Sql.value(type, null), type, SqlType.fromJavaType(type), true);
    assertResult(Sql.value(Sql.parameter(type)), type, SqlType.fromJavaType(type), true);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void assertCastTarget(Object value) {
    Class type = value.getClass();
    assertResult(Sql.cast(Sql.value(value), type, SqlType.fromJavaType(type)),
        type, SqlType.fromJavaType(type), false);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void assertIncompatibleResultsRejected() {
    Selectable left = Sql.value(1);
    Selectable right = Sql.value(2L);
    assertTypeFailure(() -> Sql.caseWhen(TABLE.id().isNotNull(), left, right));
    assertTypeFailure(() -> Sql.coalesce(left, right));
    assertTypeFailure(() -> Sql.add(left, right));
  }

  private static Selectable<Object> otherValue() {
    return new ExpressionSelectable<>(new ParameterSlot<>(0, Object.class, SqlType.OTHER, Nullability.NULLABLE));
  }

  private static void assertResult(Selectable<?> value, Class<?> javaType, SqlType sqlType, boolean nullable) {
    assertEquals(javaType, value.javaType());
    assertEquals(sqlType, value.sqlType());
    assertEquals(nullable, value.nullability().isNullable());
  }

  private static void assertTypeFailure(Runnable action) {
    assertEquals(SkisErrorCode.QUERY_TYPE_MISMATCH, assertThrows(QueryValidationException.class, action::run).code());
  }

  private static void assertCaptured(Runnable action) {
    QueryValidationException failure = assertThrows(QueryValidationException.class, action::run);
    assertEquals(SkisErrorCode.QUERY_CAPTURED_VALUE, failure.code());
    assertFalse(failure.getMessage().contains("sensitive-sentinel"));
  }

  private static void assertConstructionCode(SkisErrorCode code, Runnable action) {
    QueryValidationException failure = assertThrows(QueryValidationException.class, action::run);
    assertEquals(code, failure.code());
    assertEquals(SkisDiagnosticStage.QUERY_CONSTRUCTION, failure.diagnostics().stage());
  }

  private static CompiledExpression compile(Selectable<?> expression) {
    StatementParameterLayout layout = new StatementParameterLayout();
    QueryParameterBindings bindings = new QueryParameterBindings();
    SqlExpression<?> ast = new QueryConditionCompiler(layout, bindings).expression(expression);
    return new CompiledExpression(ast, layout.parameterReferences(), bindings.parameters());
  }

  private static CompiledExpression captureAndCompile(Selectable<?> expression) {
    ExpressionCapture capture = new ExpressionCapture();
    CompiledExpression compiled = compile(capture.selectable(expression));
    assertTrue(compiled.parameters().isEmpty());
    return new CompiledExpression(compiled.expression(), compiled.references(), capture.parameters());
  }

  private static void analyze(CompiledQueryStructure structure) {
    ResolvedStatement.analyze(new SelectStatement(false, structure.selections(), List.of(),
        structure.fromClause(), structure.where(), structure.groupBy(), structure.having(),
        structure.orderBy(), null));
  }

  private record CompiledExpression(SqlExpression<?> expression, List<QueryParameter<?>> references,
      QueryParameters parameters) {
    QueryArguments arguments() {
      return parameters.valuesFor(references);
    }
  }

  private static final class CustomDecimal extends BigDecimal {
    private static final long serialVersionUID = 1L;
    CustomDecimal() { super("1"); }
  }

  private record Sample(Integer id, String label) {}

  private static final class SampleTable extends QueryTable<Sample> {
    private static final PropertyMeta<Sample, Integer> ID =
        new PropertyMeta<>(0, "id", Integer.class, ColumnMeta.of("id", false));
    private static final PropertyMeta<Sample, String> LABEL =
        new PropertyMeta<>(1, "label", String.class, ColumnMeta.of("label", true));
    private static final EntityMeta<Sample> ENTITY = EntityMeta.simple(Sample.class,
        new TableMeta("", "", "sample"), List.of(ID, LABEL), new PrimaryKeyMeta<>(List.of(ID)), false);

    SampleTable() { super(ENTITY); }
    SampleTable(String alias) { super(ENTITY, alias); }
    @Override public SampleTable as(String alias) { return new SampleTable(alias); }
    NonNullSelectable<Integer> id() { return nonNullQueryColumn(ID); }
    Selectable<String> label() { return queryColumn(LABEL); }
  }
}
