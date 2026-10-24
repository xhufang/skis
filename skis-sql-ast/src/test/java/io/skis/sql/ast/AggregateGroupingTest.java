package io.skis.sql.ast;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnostics;
import io.skis.core.SkisDiagnostics.QuerySemanticReason;
import io.skis.core.SkisErrorCode;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Complete, database-independent grouping and aggregate-ownership contracts. */
class AggregateGroupingTest {

  private static final PropertyMeta<Row, Integer> ID =
      new PropertyMeta<>(0, "id", Integer.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Row, Integer> CATEGORY =
      new PropertyMeta<>(1, "category", Integer.class, ColumnMeta.of("category", true));
  private static final PropertyMeta<Row, Integer> AMOUNT =
      new PropertyMeta<>(2, "amount", Integer.class, ColumnMeta.of("amount", true));
  private static final PropertyMeta<Row, String> NAME =
      new PropertyMeta<>(3, "name", String.class, ColumnMeta.of("name", true));
  private static final EntityMeta<Row> MODEL =
      EntityMeta.simple(
          Row.class,
          new TableMeta("", "", "grouping_row"),
          List.of(ID, CATEGORY, AMOUNT, NAME),
          new PrimaryKeyMeta<>(List.of(ID)),
          false);

  @Test
  void acceptsGlobalAggregatesAndPreservesTheirResolvedDescriptors() {
    TestTable table = new TestTable("t");
    SelectStatement statement =
        new SelectStatement(
            List.of(
                AggregateExpression.countAll(),
                AggregateExpression.count(table.amount),
                AggregateExpression.countDistinct(table.amount),
                AggregateExpression.sumAsLong(table.amount),
                AggregateExpression.avgAsBigDecimal(table.amount),
                AggregateExpression.min(table.amount),
                AggregateExpression.max(table.amount)),
            table);
    QueryBlockAnalysis analysis = valid(statement);

    assertEquals(Nullability.NON_NULL, analysis.expressions().getFirst().effectiveNullability());
    assertTrue(analysis.expressions().getFirst().columnDependencies().isEmpty());
    for (int index = 1; index < analysis.expressions().size(); index++) {
      assertEquals(1, analysis.expressions().get(index).columnDependencies().size());
      assertEquals(
          index < 3 ? Nullability.NON_NULL : Nullability.NULLABLE,
          analysis.expressions().get(index).effectiveNullability());
    }
    assertNotEquals(
        analysis.expressions().get(1).structureKey(), analysis.expressions().get(2).structureKey());
  }

  @Test
  void rejectsNonGroupedColumnsBesideGlobalAggregates() {
    TestTable table = new TestTable("t");
    QueryValidationException failure =
        grouping(
            new SelectStatement(List.of(AggregateExpression.countAll(), table.amount), table),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);

    assertEquals(2, failure.diagnostics().querySemantics().missingPropertyOrdinal());
    assertEquals(0, failure.diagnostics().querySemantics().missingDependency().sourceIndex());
    assertTrue(failure.diagnostics().query().expressionPath().contains("SELECT item #1"));
  }

  @Test
  void groupedColumnsCanBeCombinedWithEachOtherAndParameters() {
    TestTable table = new TestTable("t");
    ParameterSlot<Integer> offset = new ParameterSlot<>(0, Integer.class, false);
    valid(
        grouped(
            table,
            List.of(
                add(table.id, table.category),
                add(table.id, offset),
                AggregateExpression.countAll()),
            List.of(table.id, table.category)));
  }

  @Test
  void matchesRebuiltCompoundKeysWithoutUsingExpressionObjectIdentity() {
    TestTable table = new TestTable("t");
    valid(
        grouped(
            table,
            List.of(add(table.id, LiteralExpression.one(Integer.class))),
            List.of(add(table.id, LiteralExpression.one(Integer.class)))));
  }

  @Test
  void doesNotInvertCompoundKeysOrApplyCommutativity() {
    TestTable table = new TestTable("t");
    SqlExpression<Integer> key = add(table.id, LiteralExpression.one(Integer.class));
    grouping(
        grouped(table, List.of(table.id), List.of(key)),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.SELECT);
    grouping(
        grouped(
            table,
            List.of(add(LiteralExpression.one(Integer.class), table.id)),
            List.of(key)),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.SELECT);
  }

  @Test
  void rejectsDuplicateCompoundKeysEvenWhenTheyAreRebuilt() {
    TestTable table = new TestTable("t");
    grouping(
        grouped(
            table,
            List.of(AggregateExpression.countAll()),
            List.of(
                add(table.id, LiteralExpression.one(Integer.class)),
                add(table.id, LiteralExpression.one(Integer.class)))),
        QuerySemanticReason.DUPLICATE_GROUP_KEY,
        QueryClause.GROUP_BY);
  }

  @Test
  void matchesLogicalParameterSlotsButNeverMergesIndependentSlots() {
    TestTable table = new TestTable("t");
    ParameterSlot<Integer> first = new ParameterSlot<>(0, Integer.class, false);
    ParameterSlot<Integer> repeated = new ParameterSlot<>(0, Integer.class, false);
    ParameterSlot<Integer> other = new ParameterSlot<>(1, Integer.class, false);
    valid(grouped(table, List.of(add(table.id, repeated)), List.of(add(table.id, first))));
    grouping(
        grouped(table, List.of(add(table.id, other)), List.of(add(table.id, first))),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.SELECT);
    // Independent row-invariant values are legal without being equivalent to the grouping key.
    valid(grouped(table, List.of(other), List.of(first)));
  }

  @Test
  void acceptsPureParametersControlledConstantsTypedNullAndMixedKeys() {
    TestTable table = new TestTable("t");
    ParameterSlot<Integer> parameter = new ParameterSlot<>(0, Integer.class, true);
    SqlExpression<String> nullKey = LiteralExpression.nullLiteral(String.class);
    SelectStatement pure =
        grouped(
            table,
            List.of(parameter, nullKey, AggregateExpression.countAll()),
            List.of(parameter, nullKey, LiteralExpression.zero(Integer.class)));
    valid(pure);
    assertEquals(3, pure.groupBy().size());
    valid(
        grouped(
            table,
            List.of(table.category, parameter, AggregateExpression.countAll()),
            List.of(table.category, parameter)));
  }

  @Test
  void acceptsStandardCaseCoalesceCastConcatenationAndBooleanKeys() {
    TestTable table = new TestTable("t");
    SqlExpression<Integer> category =
        new CoalesceExpression<>(List.of(table.category, LiteralExpression.zero(Integer.class)));
    SqlExpression<Long> cast = new CastExpression<>(table.id, Long.class, SqlType.BIGINT);
    SqlExpression<String> name =
        new ConcatExpression(List.of(table.name, LiteralExpression.nullLiteral(String.class)));
    SqlPredicate condition = table.amount.isNotNull();
    SqlExpression<Integer> level =
        new CaseExpression<>(List.of(new CaseWhen<>(condition, table.id)), table.category);
    List<SqlExpression<?>> keys = List.of(category, cast, name, condition, level);
    valid(grouped(table, keys, keys));
  }

  @Test
  void havingAloneCreatesAGlobalGroupEvenWithoutAnAggregate() {
    TestTable table = new TestTable("t");
    valid(
        statement(
            FromClause.of(table),
            List.of(LiteralExpression.one(Integer.class)),
            List.of(),
            null,
            new BooleanPredicate(true),
            List.of(),
            List.of()));
    grouping(
        statement(
            FromClause.of(table),
            List.of(table.id),
            List.of(),
            null,
            new BooleanPredicate(true),
            List.of(),
            List.of()),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.SELECT);
  }

  @Test
  void checksHavingAndOrderingDependenciesAgainstTheGroupingKeys() {
    TestTable table = new TestTable("t");
    grouping(
        statement(
            FromClause.of(table),
            List.of(table.id),
            List.of(table.id),
            null,
            table.amount.isNotNull(),
            List.of(),
            List.of()),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.HAVING);
    grouping(
        statement(
            FromClause.of(table),
            List.of(table.id),
            List.of(table.id),
            null,
            null,
            List.of(order(table.amount)),
            List.of()),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.ORDER_BY);
  }

  @Test
  void aggregatesOnlyInOrderOrHiddenSelectionsStillGroupTheCurrentBlock() {
    TestTable table = new TestTable("t");
    grouping(
        statement(
            FromClause.of(table),
            List.of(table.id),
            List.of(),
            null,
            null,
            List.of(order(AggregateExpression.countAll())),
            List.of()),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.SELECT);
    grouping(
        statement(
            FromClause.of(table),
            List.of(table.id),
            List.of(),
            null,
            null,
            List.of(),
            List.of(
                new HiddenSelection(
                    AggregateExpression.countAll(), Identifier.of("hidden_count")))),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.SELECT);
  }

  @Test
  void hiddenNonGroupedColumnsCannotEscapeValidation() {
    TestTable table = new TestTable("t");
    QueryValidationException failure =
        grouping(
            statement(
                FromClause.of(table),
                List.of(AggregateExpression.countAll()),
                List.of(),
                null,
                null,
                List.of(),
                List.of(new HiddenSelection(table.amount, Identifier.of("hidden_amount")))),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);
    assertTrue(failure.diagnostics().query().expressionPath().contains("SELECT item #1"));
  }

  @Test
  void rejectsDirectAggregatesInWhereJoinOnAndGroupBy() {
    TestTable table = new TestTable("t");
    TestTable joined = new TestTable("j");
    SqlPredicate aggregatePredicate =
        new ComparisonPredicate<>(
            AggregateExpression.countAll(),
            ComparisonOperator.GREATER_THAN,
            LiteralExpression.zero(Long.class));
    aggregate(
        statement(
            FromClause.of(table),
            List.of(table.id),
            List.of(),
            aggregatePredicate,
            null,
            List.of(),
            List.of()),
        QuerySemanticReason.AGGREGATE_IN_CLAUSE,
        QueryClause.WHERE);
    aggregate(
        new SelectStatement(
            List.of(table.id),
            new FromClause(
                table, List.of(new JoinClause(JoinType.INNER, joined, aggregatePredicate)))),
        QuerySemanticReason.AGGREGATE_IN_CLAUSE,
        QueryClause.JOIN_ON);
    aggregate(
        grouped(
            table,
            List.of(AggregateExpression.countAll()),
            List.of(AggregateExpression.countAll())),
        QuerySemanticReason.AGGREGATE_IN_CLAUSE,
        QueryClause.GROUP_BY);
  }

  @Test
  void rejectsSameBlockNestingAcrossAllStandardWrappers() {
    TestTable table = new TestTable("t");
    List<SqlExpression<?>> nested =
        List.of(
            AggregateExpression.count(AggregateExpression.max(table.amount)),
            AggregateExpression.sumAsBigInteger(AggregateExpression.sumAsLong(table.amount)),
            AggregateExpression.min(
                new CoalesceExpression<>(
                    List.of(AggregateExpression.countAll(), LiteralExpression.zero(Long.class)))));
    for (SqlExpression<?> expression : nested) {
      aggregate(
          new SelectStatement(List.of(expression), table),
          QuerySemanticReason.NESTED_AGGREGATE,
          QueryClause.SELECT);
    }
  }

  @Test
  void independentCountAstCannotNestAnAggregateInItsDistinctInput() {
    TestTable table = new TestTable("t");
    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class,
            () ->
                SemanticValidator.analyzeComplete(
                    new CountAst(table, null, AggregateExpression.max(table.amount))));
    assertEquals(SkisErrorCode.QUERY_AGGREGATE_CONTRACT, failure.code());
    assertEquals(
        QuerySemanticReason.NESTED_AGGREGATE, failure.diagnostics().querySemantics().reason());
  }

  @Test
  void allowsIndependentAggregationAcrossADerivedBoundary() {
    TestTable table = new TestTable("i");
    SelectStatement inner =
        new SelectStatement(List.of(AggregateExpression.max(table.amount)), table);
    DerivedRelationReference reference =
        new DerivedRelationReference(
            Identifier.of("d"),
            List.of(
                new DerivedOutputColumn(
                    Identifier.of("maximum"),
                    Integer.class,
                    SqlType.INTEGER,
                    Nullability.NULLABLE)));
    DerivedColumnExpression<Integer> maximum = new DerivedColumnExpression<>(reference, 0);
    valid(
        new SelectStatement(
            List.of(AggregateExpression.sumAsLong(maximum)),
            new FromClause(new DerivedRelationSource(inner, reference), List.of())));
  }

  @Test
  void subqueryOnlyAggregatesDoNotGroupTheirParentBlock() {
    TestTable outer = new TestTable("o");
    TestTable inner = new TestTable("i");
    ScalarSubqueryExpression<Long> count =
        new ScalarSubqueryExpression<>(
            new SelectStatement(List.of(AggregateExpression.countAll()), inner));
    valid(new SelectStatement(List.of(outer.id, count), outer));
  }

  @Test
  void whereAndJoinOnCanContainIndependentAggregateSubqueries() {
    TestTable outer = new TestTable("o");
    TestTable inner = new TestTable("i");
    TestTable joined = new TestTable("j");
    SelectStatement child =
        new SelectStatement(
            List.of(AggregateExpression.countAll()), inner, inner.category.eq(outer.amount));
    SqlPredicate exists = new ExistsPredicate(child, false);
    valid(
        statement(
            FromClause.of(outer),
            List.of(outer.id),
            List.of(outer.id),
            exists,
            null,
            List.of(),
            List.of()));
    valid(
        statement(
            new FromClause(outer, List.of(new JoinClause(JoinType.LEFT, joined, exists))),
            List.of(outer.id),
            List.of(outer.id),
            null,
            null,
            List.of(),
            List.of()));
  }

  @Test
  void correlatedSubqueryOutputRequiresItsAncestorDependenciesToBeGrouped() {
    TestTable outer = new TestTable("o");
    TestTable inner = new TestTable("i");
    ScalarSubqueryExpression<Long> safe = countWhere(inner, inner.category.eq(outer.id));
    valid(grouped(outer, List.of(outer.id, safe), List.of(outer.id)));
    ScalarSubqueryExpression<Long> unsafe = countWhere(inner, inner.category.eq(outer.amount));
    QueryValidationException failure =
        grouping(
            grouped(outer, List.of(outer.id, unsafe), List.of(outer.id)),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);
    assertEquals("$", failure.diagnostics().querySemantics().missingDependency().blockPath());
    assertEquals(2, failure.diagnostics().querySemantics().missingPropertyOrdinal());
  }

  @Test
  void inSubqueryChecksItsValueAsAnExpressionBeforeItsCorrelatedDependencies() {
    TestTable outer = new TestTable("o");
    TestTable inner = new TestTable("i");
    SqlExpression<Integer> key = add(outer.id, LiteralExpression.one(Integer.class));
    SelectStatement independent = new SelectStatement(List.of(inner.id), inner);
    valid(
        grouped(
            outer,
            List.of(
                new InSubqueryPredicate<>(
                    add(outer.id, LiteralExpression.one(Integer.class)), independent, false)),
            List.of(key)));
    SelectStatement correlated =
        new SelectStatement(List.of(inner.id), inner, inner.category.eq(outer.amount));
    grouping(
        grouped(
            outer,
            List.of(new InSubqueryPredicate<>(key, correlated, false)),
            List.of(key)),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.SELECT);
    QueryValidationException failure =
        grouping(
            grouped(
                outer,
                List.of(new InSubqueryPredicate<>(outer.category, correlated, false)),
                List.of(outer.id)),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);
    assertEquals(1, failure.diagnostics().querySemantics().missingPropertyOrdinal());
    assertTrue(failure.diagnostics().query().expressionPath().contains("operand #0"));
  }

  @Test
  void existsInHavingAndOrderByRetainsAncestorGroupingRequirements() {
    TestTable outer = new TestTable("o");
    TestTable inner = new TestTable("i");
    SqlPredicate safe =
        new ExistsPredicate(
            new SelectStatement(List.of(inner.id), inner, inner.category.eq(outer.id)), false);
    CaseExpression<Integer> safeOrder =
        new CaseExpression<>(
            List.of(new CaseWhen<>(safe, LiteralExpression.one(Integer.class))),
            LiteralExpression.zero(Integer.class));
    valid(
        statement(
            FromClause.of(outer),
            List.of(outer.id),
            List.of(outer.id),
            null,
            safe,
            List.of(order(safeOrder)),
            List.of()));
    SqlPredicate unsafe =
        new ExistsPredicate(
            new SelectStatement(List.of(inner.id), inner, inner.category.eq(outer.amount)), false);
    CaseExpression<Integer> unsafeOrder =
        new CaseExpression<>(
            List.of(new CaseWhen<>(unsafe, LiteralExpression.one(Integer.class))),
            LiteralExpression.zero(Integer.class));
    grouping(
        statement(
            FromClause.of(outer),
            List.of(outer.id),
            List.of(outer.id),
            null,
            unsafe,
            List.of(),
            List.of()),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.HAVING);
    QueryValidationException failure =
        grouping(
            statement(
                FromClause.of(outer),
                List.of(outer.id),
                List.of(outer.id),
                null,
                null,
                List.of(order(unsafeOrder)),
                List.of()),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.ORDER_BY);
    assertEquals(AMOUNT.ordinal(), failure.diagnostics().querySemantics().missingPropertyOrdinal());
    assertEquals(
        "$ ORDER BY item #0 operand #0", failure.diagnostics().query().expressionPath());
  }

  @Test
  void innerAggregateDoesNotProtectDependenciesBelongingToAnAncestor() {
    TestTable outer = new TestTable("o");
    TestTable inner = new TestTable("i");
    SelectStatement child =
        new SelectStatement(
            List.of(AggregateExpression.max(add(inner.amount, outer.amount))), inner);
    grouping(
        grouped(outer, List.of(new ScalarSubqueryExpression<Integer>(child)), List.of(outer.id)),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.SELECT);
  }

  @Test
  void rejectsAggregatesWithOnlyAncestorFreeColumns() {
    TestTable outer = new TestTable("o");
    TestTable inner = new TestTable("i");
    SelectStatement child =
        new SelectStatement(List.of(AggregateExpression.max(outer.amount)), inner);
    QueryValidationException failure =
        aggregate(
            new SelectStatement(List.of(new ScalarSubqueryExpression<Integer>(child)), outer),
            QuerySemanticReason.OUTER_ONLY_AGGREGATE,
            QueryClause.SELECT);
    assertEquals("MAX", failure.diagnostics().querySemantics().aggregateKind());
    assertEquals("$/SELECT[0]#0", failure.diagnostics().querySemantics().aggregateBlockPath());
  }

  @Test
  void unrelatedSubqueryLocalColumnsDoNotBecomeTheEnclosingAggregateInputRows() {
    TestTable outer = new TestTable("o");
    TestTable middle = new TestTable("m");
    TestTable inner = new TestTable("i");
    SelectStatement unrelated = new SelectStatement(List.of(inner.amount), inner);
    SelectStatement legal =
        new SelectStatement(
            List.of(
                AggregateExpression.sumAsLong(new ScalarSubqueryExpression<Integer>(unrelated))),
            middle);
    valid(new SelectStatement(List.of(outer.id, new ScalarSubqueryExpression<Long>(legal)), outer));
    SelectStatement correlated =
        new SelectStatement(List.of(inner.amount), inner, inner.id.eq(outer.amount));
    SelectStatement ambiguous =
        new SelectStatement(
            List.of(
                AggregateExpression.sumAsLong(new ScalarSubqueryExpression<Integer>(correlated))),
            middle);
    aggregate(
        new SelectStatement(List.of(new ScalarSubqueryExpression<Long>(ambiguous)), outer),
        QuerySemanticReason.OUTER_ONLY_AGGREGATE,
        QueryClause.SELECT);
  }

  @Test
  void ancestorAggregateProtectsItsOwnInputEvenThroughACorrelatedSubquery() {
    TestTable outer = new TestTable("o");
    TestTable inner = new TestTable("i");
    ScalarSubqueryExpression<Long> child = countWhere(inner, inner.category.eq(outer.amount));
    valid(
        grouped(
            outer,
            List.of(outer.id, AggregateExpression.sumAsBigInteger(child)),
            List.of(outer.id)));
  }

  @Test
  void reusedSubqueryOccurrencesDoNotShareAggregateProtectionOrWriteBackToAst() {
    TestTable outer = new TestTable("o");
    TestTable inner = new TestTable("i");
    ScalarSubqueryExpression<Long> child = countWhere(inner, inner.category.eq(outer.amount));
    int originalHash = child.hashCode();
    SelectStatement legal =
        grouped(outer, List.of(AggregateExpression.sumAsBigInteger(child)), List.of(outer.id));
    QueryBlockAnalysis first = valid(legal);
    grouping(
        grouped(
            outer, List.of(AggregateExpression.sumAsBigInteger(child), child), List.of(outer.id)),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.SELECT);
    assertEquals(originalHash, child.hashCode());
    assertEquals(first.structureKey(), valid(legal).structureKey());
  }

  @Test
  void multiLevelSubqueriesKeepFreeDependenciesOfEveryAncestor() {
    TestTable outer = new TestTable("o");
    TestTable middle = new TestTable("m");
    TestTable inner = new TestTable("i");
    ScalarSubqueryExpression<Long> innerCount = countWhere(inner, inner.category.eq(outer.amount));
    SelectStatement child = new SelectStatement(List.of(innerCount), middle);
    grouping(
        grouped(outer, List.of(new ScalarSubqueryExpression<Long>(child)), List.of(outer.id)),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.SELECT);
  }

  @Test
  void threeLevelAggregatesProtectOnlyTheirOwnInputsAcrossReusedSubqueries() {
    TestTable outer = new TestTable("o");
    TestTable middle = new TestTable("m");
    TestTable inner = new TestTable("i");
    ScalarSubqueryExpression<Integer> innerValue =
        new ScalarSubqueryExpression<>(
            new SelectStatement(
                List.of(
                    AggregateExpression.max(
                        add(
                            add(add(inner.amount, middle.amount), outer.amount),
                            new ParameterSlot<>(0, Integer.class, false)))),
                inner));
    ScalarSubqueryExpression<Integer> middleValue =
        new ScalarSubqueryExpression<>(
            new SelectStatement(
                List.of(
                    AggregateExpression.max(
                        add(innerValue, new ParameterSlot<>(1, Integer.class, false)))),
                middle));
    AggregateExpression<Long> total =
        AggregateExpression.sumAsLong(
            add(middleValue, new ParameterSlot<>(2, Integer.class, false)));
    QueryBlockAnalysis analysis =
        valid(grouped(outer, List.of(outer.id, total), List.of(outer.id)));

    List<ResolvedColumnReference> dependencies = analysis.expressions().get(1).columnDependencies();
    assertEquals(1, dependencies.size());
    ResolvedColumnIdentity dependency = (ResolvedColumnIdentity) dependencies.getFirst();
    assertEquals(QueryBlockPath.root(), dependency.source().blockPath());
    assertEquals(AMOUNT.ordinal(), dependency.propertyOrdinal());
    List<ResolvedParameterIdentity> parameters = analysis.expressions().get(1).parameterDependencies();
    assertEquals(3, parameters.size());
    for (int index = 0; index < parameters.size(); index++) {
      assertEquals(index, parameters.get(index).parameterOrdinal());
    }
    assertEquals("$/SELECT[1]#0/SELECT[0]#0", parameters.get(0).blockPath().toString());
    assertEquals("$/SELECT[1]#0", parameters.get(1).blockPath().toString());
    assertEquals(QueryBlockPath.root(), parameters.get(2).blockPath());
    QueryValidationException failure =
        grouping(
            grouped(outer, List.of(total, middleValue), List.of(outer.id)),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);
    assertEquals("$", failure.diagnostics().querySemantics().missingDependency().blockPath());
    assertEquals(AMOUNT.ordinal(), failure.diagnostics().querySemantics().missingPropertyOrdinal());
    assertTrue(failure.diagnostics().query().expressionPath().contains("SELECT item #1"));
  }

  @Test
  void outerAggregateDoesNotProtectAnIntermediateGroupsUnaggregatedInput() {
    TestTable outer = new TestTable("o");
    TestTable middle = new TestTable("m");
    TestTable inner = new TestTable("i");
    ScalarSubqueryExpression<Integer> innerValue =
        new ScalarSubqueryExpression<>(
            new SelectStatement(
                List.of(
                    AggregateExpression.max(add(add(inner.amount, middle.amount), outer.amount))),
                inner));
    SelectStatement middleQuery =
        grouped(
            middle,
            List.of(add(AggregateExpression.max(innerValue), innerValue)),
            List.of(middle.id));
    QueryValidationException failure =
        grouping(
            grouped(
                outer,
                List.of(
                    AggregateExpression.sumAsLong(
                        new ScalarSubqueryExpression<Integer>(middleQuery))),
                List.of(outer.id)),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);

    assertEquals("$/SELECT[0]#0", failure.diagnostics().query().blockPath());
    assertEquals(
        "$/SELECT[0]#0", failure.diagnostics().querySemantics().missingDependency().blockPath());
    assertEquals(AMOUNT.ordinal(), failure.diagnostics().querySemantics().missingPropertyOrdinal());
    assertTrue(failure.diagnostics().query().expressionPath().contains("operand #1"));
  }

  @Test
  void outerOnlyAggregateCannotBorrowALocalDependencyFromASiblingOperand() {
    TestTable outer = new TestTable("o");
    TestTable inner = new TestTable("i");
    SelectStatement child =
        new SelectStatement(
            List.of(add(inner.amount, AggregateExpression.max(outer.amount))), inner);

    aggregate(
        new SelectStatement(List.of(new ScalarSubqueryExpression<Integer>(child)), outer),
        QuerySemanticReason.OUTER_ONLY_AGGREGATE,
        QueryClause.SELECT);
  }

  @Test
  void compoundAncestorKeysDoNotGrantGroupingToTheirIndividualColumns() {
    TestTable outer = new TestTable("o");
    TestTable inner = new TestTable("i");
    ScalarSubqueryExpression<Long> child =
        countWhere(inner, inner.category.eq(add(outer.id, LiteralExpression.one(Integer.class))));
    grouping(
        grouped(
            outer, List.of(child), List.of(add(outer.id, LiteralExpression.one(Integer.class)))),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.SELECT);
  }

  @Test
  void groupByRejectsSubqueriesAndSelectedOutputReferencesEvenWhenWrapped() {
    TestTable table = new TestTable("t");
    TestTable inner = new TestTable("i");
    ScalarSubqueryExpression<Long> child = countWhere(inner, null);
    List<SqlExpression<?>> invalid =
        List.of(
            child,
            new CoalesceExpression<>(List.of(child, LiteralExpression.zero(Long.class))),
            new ExistsPredicate(child.subquery(), false),
            new InSubqueryPredicate<>(LiteralExpression.one(Long.class), child.subquery(), false),
            SelectedOutputReference.of(1, table.id));
    for (SqlExpression<?> key : invalid) {
      grouping(
          grouped(table, List.of(AggregateExpression.countAll()), List.of(key)),
          QuerySemanticReason.INVALID_GROUP_KEY,
          QueryClause.GROUP_BY);
    }
  }

  @Test
  void groupByRejectsAnAncestorColumnInsteadOfTreatingItAsAConstant() {
    TestTable outer = new TestTable("o");
    TestTable inner = new TestTable("i");
    SelectStatement child =
        grouped(inner, List.of(AggregateExpression.countAll()), List.of(outer.id));
    grouping(
        new SelectStatement(List.of(new ScalarSubqueryExpression<Long>(child)), outer),
        QuerySemanticReason.INVALID_GROUP_KEY,
        QueryClause.GROUP_BY);
  }

  @Test
  void joinOccurrencesRemainDistinctAndOuterJoinKeysKeepEffectiveNullability() {
    TestTable root = new TestTable("r");
    TestTable right = new TestTable("j");
    FromClause from =
        new FromClause(root, List.of(new JoinClause(JoinType.LEFT, right, root.id.eq(right.id))));
    QueryValidationException failure =
        grouping(
            statement(from, List.of(right.id), List.of(root.id), null, null, List.of(), List.of()),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);
    assertEquals(1, failure.diagnostics().querySemantics().missingDependency().sourceIndex());
    QueryBlockAnalysis analysis =
        valid(
            statement(
                from,
                List.of(
                    right.id,
                    AggregateExpression.count(right.id),
                    AggregateExpression.sumAsLong(right.amount)),
                List.of(right.id),
                null,
                null,
                List.of(),
                List.of()));
    assertEquals(Nullability.NULLABLE, analysis.expressions().get(1).effectiveNullability());
    assertTrue(analysis.sourceOccurrences().get(1).nullExtended());
  }

  @Test
  void multiLevelAggregatesPreserveAncestorOuterJoinNullabilityAndGrouping() {
    TestTable outer = new TestTable("o");
    TestTable right = new TestTable("r");
    TestTable middle = new TestTable("m");
    TestTable inner = new TestTable("i");
    FromClause from =
        new FromClause(outer, List.of(new JoinClause(JoinType.LEFT, right, outer.id.eq(right.id))));
    SqlExpression<Integer> ancestorKey = right.id;
    for (int index = 0; index < 96; index++) {
      ancestorKey = add(ancestorKey, LiteralExpression.one(Integer.class));
    }
    SelectStatement innerQuery =
        statement(
            FromClause.of(inner),
            List.of(AggregateExpression.max(add(inner.amount, right.id))),
            List.of(),
            inner.category.eq(middle.category),
            null,
            List.of(order(ancestorKey)),
            List.of());
    ScalarSubqueryExpression<Integer> middleValue =
        new ScalarSubqueryExpression<>(
            new SelectStatement(
                List.of(AggregateExpression.max(new ScalarSubqueryExpression<Integer>(innerQuery))),
                middle));
    List<SqlExpression<?>> selections = List.of(outer.id, middleValue);
    QueryBlockAnalysis analysis =
        valid(
            statement(
                from,
                selections,
                List.of(outer.id, right.id),
                null,
                null,
                List.of(),
                List.of()));

    assertEquals(Nullability.NON_NULL, right.id.nullability());
    assertTrue(analysis.sourceOccurrences().get(1).nullExtended());
    ResolvedExpression selected = analysis.expressions().get(2);
    assertEquals(Nullability.NULLABLE, selected.effectiveNullability());
    assertEquals(1, selected.columnDependencies().size());
    ResolvedColumnIdentity dependency =
        (ResolvedColumnIdentity) selected.columnDependencies().getFirst();
    assertEquals(QueryBlockPath.root(), dependency.source().blockPath());
    assertEquals(1, dependency.source().occurrenceOrdinal());
    assertEquals(ID.ordinal(), dependency.propertyOrdinal());
    QueryBlockAnalysis innerAnalysis =
        analysis.nestedBlocks().getFirst().analysis().nestedBlocks().getFirst().analysis();
    ResolvedExpression ordered = innerAnalysis.expressions().getLast();
    assertEquals(QueryClause.ORDER_BY, ordered.position().clause());
    assertEquals(Nullability.NULLABLE, ordered.effectiveNullability());
    assertEquals(List.of(dependency), ordered.columnDependencies());

    QueryBlockAnalysis aggregated =
        valid(
            statement(
                from,
                List.of(outer.id, AggregateExpression.sumAsLong(middleValue)),
                List.of(outer.id),
                null,
                null,
                List.of(),
                List.of()));
    assertEquals(Nullability.NULLABLE, aggregated.expressions().get(2).effectiveNullability());
    assertEquals(
        selected.columnDependencies(), aggregated.expressions().get(2).columnDependencies());

    QueryValidationException failure =
        grouping(
            statement(
                from, selections, List.of(outer.id), null, null, List.of(), List.of()),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);
    SkisDiagnostics.QuerySemantics semantics = failure.diagnostics().querySemantics();
    assertEquals("$ SELECT item #1", failure.diagnostics().query().expressionPath());
    assertEquals("$", semantics.missingDependency().blockPath());
    assertEquals(1, semantics.missingDependency().sourceIndex());
    assertEquals(ID.ordinal(), semantics.missingPropertyOrdinal());
    assertNull(semantics.missingOutputOrdinal());
  }

  @Test
  void correlatedDerivedOutputsKeepTheirGroupingAndAggregateOwnershipAcrossLevels() {
    TestTable source = new TestTable("s");
    TestTable middle = new TestTable("m");
    TestTable inner = new TestTable("i");
    DerivedRelationReference reference =
        new DerivedRelationReference(
            Identifier.of("d"),
            List.of(
                new DerivedOutputColumn(
                    Identifier.of("id"), Integer.class, SqlType.INTEGER, Nullability.NON_NULL),
                new DerivedOutputColumn(
                    Identifier.of("amount"),
                    Integer.class,
                    SqlType.INTEGER,
                    Nullability.NULLABLE)));
    FromClause from =
        new FromClause(
            new DerivedRelationSource(
                new SelectStatement(List.of(source.id, source.amount), source), reference),
            List.of());
    DerivedColumnExpression<Integer> id = new DerivedColumnExpression<>(reference, 0);
    DerivedColumnExpression<Integer> amount = new DerivedColumnExpression<>(reference, 1);
    ScalarSubqueryExpression<Integer> innerValue =
        new ScalarSubqueryExpression<>(
            new SelectStatement(
                List.of(AggregateExpression.max(add(inner.amount, amount))),
                inner,
                inner.category.eq(middle.category)));
    ScalarSubqueryExpression<Integer> middleValue =
        new ScalarSubqueryExpression<>(
            new SelectStatement(List.of(AggregateExpression.max(innerValue)), middle));
    List<SqlExpression<?>> selections = List.of(id, middleValue);
    QueryBlockAnalysis analysis =
        valid(
            statement(
                from, selections, List.of(id, amount), null, null, List.of(), List.of()));

    ResolvedExpression selected = analysis.expressions().get(1);
    assertEquals(Nullability.NULLABLE, selected.effectiveNullability());
    assertEquals(1, selected.columnDependencies().size());
    ResolvedDerivedColumnIdentity dependency =
        (ResolvedDerivedColumnIdentity) selected.columnDependencies().getFirst();
    assertEquals(QueryBlockPath.root(), dependency.source().blockPath());
    assertEquals(0, dependency.source().occurrenceOrdinal());
    assertEquals(1, dependency.outputOrdinal());

    QueryValidationException groupingFailure =
        grouping(
            statement(from, selections, List.of(id), null, null, List.of(), List.of()),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);
    SkisDiagnostics.QuerySemantics missing = groupingFailure.diagnostics().querySemantics();
    assertEquals("$ SELECT item #1", groupingFailure.diagnostics().query().expressionPath());
    assertEquals("$", missing.missingDependency().blockPath());
    assertEquals(0, missing.missingDependency().sourceIndex());
    assertEquals(1, missing.missingOutputOrdinal());
    assertNull(missing.missingPropertyOrdinal());

    ScalarSubqueryExpression<Integer> ancestorOnly =
        new ScalarSubqueryExpression<>(new SelectStatement(List.of(amount), inner));
    SelectStatement ambiguous =
        new SelectStatement(
            List.of(add(middle.amount, AggregateExpression.max(ancestorOnly))), middle);
    QueryValidationException aggregateFailure =
        aggregate(
            statement(
                from,
                List.of(id, new ScalarSubqueryExpression<Integer>(ambiguous)),
                List.of(id, amount),
                null,
                null,
                List.of(),
                List.of()),
            QuerySemanticReason.OUTER_ONLY_AGGREGATE,
            QueryClause.SELECT);
    SkisDiagnostics.QuerySemantics aggregate = aggregateFailure.diagnostics().querySemantics();
    assertEquals("MAX", aggregate.aggregateKind());
    assertEquals("$/SELECT[1]#0", aggregate.aggregateBlockPath());
    assertEquals(
        "$/SELECT[1]#0 SELECT item #0 operand #1",
        aggregateFailure.diagnostics().query().expressionPath());
    assertNull(aggregate.missingDependency());
  }

  @Test
  void derivedKeysReportOutputOrdinalsAndSupportOuterAggregation() {
    TestTable inner = new TestTable("i");
    DerivedRelationReference reference =
        new DerivedRelationReference(
            Identifier.of("d"),
            List.of(
                new DerivedOutputColumn(
                    Identifier.of("id"), Integer.class, SqlType.INTEGER, Nullability.NON_NULL),
                new DerivedOutputColumn(
                    Identifier.of("amount"),
                    Integer.class,
                    SqlType.INTEGER,
                    Nullability.NULLABLE)));
    FromClause from =
        new FromClause(
            new DerivedRelationSource(
                new SelectStatement(List.of(inner.id, inner.amount), inner), reference),
            List.of());
    DerivedColumnExpression<Integer> id = new DerivedColumnExpression<>(reference, 0);
    DerivedColumnExpression<Integer> amount = new DerivedColumnExpression<>(reference, 1);
    valid(
        statement(
            from,
            List.of(id, AggregateExpression.sumAsLong(amount)),
            List.of(id),
            null,
            null,
            List.of(),
            List.of()));
    QueryValidationException failure =
        grouping(
            statement(from, List.of(amount), List.of(id), null, null, List.of(), List.of()),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);
    assertEquals(1, failure.diagnostics().querySemantics().missingOutputOrdinal());
    assertNull(failure.diagnostics().querySemantics().missingPropertyOrdinal());
  }

  @Test
  void doesNotInferPrimaryKeyFunctionalDependenciesForExpandedEntitySelections() {
    TestTable table = new TestTable("t");
    QueryValidationException failure =
        grouping(
            grouped(
                table,
                List.of(table.id, table.category, table.amount, table.name),
                List.of(table.id)),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);
    assertEquals(1, failure.diagnostics().querySemantics().missingPropertyOrdinal());
  }

  @Test
  void illegalAggregateCannotDisappearInsideAnEmptyInOrShortCircuitedPredicate() {
    TestTable table = new TestTable("t");
    SqlPredicate emptyIn = new InPredicate<>(AggregateExpression.countAll(), List.of(), false);
    SqlPredicate predicate =
        new LogicalPredicate(LogicalOperator.AND, List.of(new BooleanPredicate(false), emptyIn));
    aggregate(
        new SelectStatement(List.of(table.id), table, predicate),
        QuerySemanticReason.AGGREGATE_IN_CLAUSE,
        QueryClause.WHERE);
  }

  @Test
  void aggregateFailuresPrecedeDuplicateAndNonGroupedKeyFailures() {
    TestTable table = new TestTable("t");
    SqlPredicate where =
        new ComparisonPredicate<>(
            AggregateExpression.countAll(),
            ComparisonOperator.GREATER_THAN,
            LiteralExpression.zero(Long.class));
    aggregate(
        statement(
            FromClause.of(table),
            List.of(table.amount),
            List.of(table.id, table.id),
            where,
            null,
            List.of(),
            List.of()),
        QuerySemanticReason.AGGREGATE_IN_CLAUSE,
        QueryClause.WHERE);
    grouping(
        grouped(table, List.of(table.amount), List.of(table.id, table.id)),
        QuerySemanticReason.DUPLICATE_GROUP_KEY,
        QueryClause.GROUP_BY);
  }

  @Test
  void parametersStillRequireADenseStatementLayoutAfterGroupingAnalysis() {
    TestTable table = new TestTable("t");
    SelectStatement statement =
        grouped(
            table,
            List.of(AggregateExpression.countAll()),
            List.of(new ParameterSlot<>(2, Integer.class, true)));
    assertThrows(
        IllegalArgumentException.class, () -> SemanticValidator.analyzeComplete(statement));
  }

  @Test
  void checksLongStandardCompositionsWithoutDroppingLeafDependencies() {
    TestTable table = new TestTable("t");
    SqlExpression<Integer> expression = table.id;
    for (int index = 0; index < 64; index++) {
      expression = add(expression, LiteralExpression.one(Integer.class));
    }
    valid(grouped(table, List.of(expression), List.of(table.id)));
    grouping(
        grouped(table, List.of(expression), List.of(table.category)),
        QuerySemanticReason.NON_GROUPED_DEPENDENCY,
        QueryClause.SELECT);
  }

  @Test
  void longCompoundKeysMatchRebuiltExpressionsAndRejectDuplicateDeclarations() {
    TestTable table = new TestTable("t");
    SqlExpression<Integer> selected = table.id;
    SqlExpression<Integer> key = table.id;
    for (int index = 0; index < 96; index++) {
      selected = add(selected, table.id);
      key = add(key, table.id);
    }

    QueryBlockAnalysis analysis = valid(grouped(table, List.of(selected), List.of(key)));
    assertEquals(1, analysis.expressions().getFirst().columnDependencies().size());
    assertEquals(
        analysis.expressions().getFirst().structureKey(),
        analysis.expressions().get(1).structureKey());
    QueryValidationException failure =
        grouping(
            grouped(table, List.of(AggregateExpression.countAll()), List.of(key, selected)),
            QuerySemanticReason.DUPLICATE_GROUP_KEY,
            QueryClause.GROUP_BY);
    assertEquals("$ GROUP BY item #1", failure.diagnostics().query().expressionPath());
  }

  @Test
  void longSharedOperandPrefixesPreserveTheExactMissingDependencyPath() {
    TestTable table = new TestTable("t");
    SqlExpression<Integer> expression = table.amount;
    int depth = 96;
    for (int index = 0; index < depth; index++) {
      expression = index % 2 == 0 ? add(expression, table.id) : add(table.id, expression);
    }
    QueryValidationException failure =
        grouping(
            grouped(table, List.of(expression), List.of(table.id)),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);

    StringBuilder expected = new StringBuilder("$ SELECT item #0");
    for (int index = depth - 1; index >= 0; index--) {
      expected.append(" operand #").append(index % 2);
    }
    assertEquals(expected.toString(), failure.diagnostics().query().expressionPath());
    assertEquals(AMOUNT.ordinal(), failure.diagnostics().querySemantics().missingPropertyOrdinal());
  }

  @Test
  void rowLevelClausesKeepDependenciesAndNullabilityBesideAGlobalAggregate() {
    TestTable table = new TestTable("t");
    TestTable joined = new TestTable("j");
    TestTable inner = new TestTable("i");
    int parameterCount = 128;
    List<SqlExpression<Integer>> candidates = new ArrayList<>();
    for (int index = 0; index < parameterCount; index++) {
      candidates.add(new ParameterSlot<>(index, Integer.class, false));
    }
    SqlPredicate where =
        new LogicalPredicate(
            LogicalOperator.AND,
            List.of(
                new InPredicate<>(add(table.id, joined.id), candidates, false),
                new ExistsPredicate(
                    new SelectStatement(
                        List.of(AggregateExpression.countAll()),
                        inner,
                        inner.category.eq(table.amount)),
                    false)));
    SelectStatement statement =
        new SelectStatement(
            false,
            List.of(AggregateExpression.countAll()),
            List.of(),
            new FromClause(
                table, List.of(new JoinClause(JoinType.LEFT, joined, table.id.eq(joined.id)))),
            where,
            List.of(),
            null,
            List.of(),
            new Limit(new ParameterSlot<>(parameterCount, Integer.class, false)));
    QueryBlockAnalysis analysis = valid(statement);

    ResolvedExpression on = analysis.expressions().getFirst();
    assertEquals(QueryClause.JOIN_ON, on.position().clause());
    assertEquals(2, on.columnDependencies().size());
    assertEquals(Nullability.NON_NULL, on.effectiveNullability());
    assertTrue(on.parameterDependencies().isEmpty());
    ResolvedExpression selected = analysis.expressions().get(1);
    assertTrue(selected.columnDependencies().isEmpty());
    assertTrue(selected.parameterDependencies().isEmpty());
    ResolvedExpression filtered = analysis.expressions().get(2);
    assertEquals(QueryClause.WHERE, filtered.position().clause());
    assertEquals(Nullability.NULLABLE, filtered.effectiveNullability());
    assertEquals(3, filtered.columnDependencies().size());
    assertEquals(parameterCount, filtered.parameterDependencies().size());
    for (int index = 0; index < parameterCount; index++) {
      assertEquals(index, filtered.parameterDependencies().get(index).parameterOrdinal());
    }
    ResolvedColumnIdentity correlation =
        (ResolvedColumnIdentity) filtered.columnDependencies().getLast();
    assertEquals(QueryBlockPath.root(), correlation.source().blockPath());
    assertEquals(AMOUNT.ordinal(), correlation.propertyOrdinal());
    ResolvedExpression limit = analysis.expressions().getLast();
    assertEquals(QueryClause.PAGINATION, limit.position().clause());
    assertTrue(limit.columnDependencies().isEmpty());
    assertEquals(Nullability.NON_NULL, limit.effectiveNullability());
    assertEquals(parameterCount, limit.parameterDependencies().getFirst().parameterOrdinal());
    for (ResolvedExpression resolved : analysis.expressions()) {
      assertTrue(resolved.position().operandPath().isEmpty());
    }
  }

  @Test
  void rowLevelTraversalStillValidatesTheGroupingInsideANestedQuery() {
    TestTable table = new TestTable("t");
    TestTable inner = new TestTable("i");
    SelectStatement child =
        new SelectStatement(List.of(inner.id, AggregateExpression.countAll()), inner);
    QueryValidationException failure =
        grouping(
            new SelectStatement(List.of(table.id), table, new ExistsPredicate(child, false)),
            QuerySemanticReason.NON_GROUPED_DEPENDENCY,
            QueryClause.SELECT);

    assertEquals("$/WHERE[0]#0", failure.diagnostics().query().blockPath());
    assertEquals(
        "$/WHERE[0]#0 SELECT item #0", failure.diagnostics().query().expressionPath());
  }

  @Test
  void rowLevelTraversalStillRejectsAnAggregateDeepInsideAnInOperand() {
    TestTable table = new TestTable("t");
    SqlExpression<Integer> value = AggregateExpression.max(table.amount);
    int depth = 96;
    for (int index = 0; index < depth; index++) {
      value = add(table.id, value);
    }
    QueryValidationException failure =
        aggregate(
            new SelectStatement(
                List.of(table.id),
                table,
                new InPredicate<>(
                    value, List.of(new ParameterSlot<>(0, Integer.class, false)), false)),
            QuerySemanticReason.AGGREGATE_IN_CLAUSE,
            QueryClause.WHERE);

    assertEquals(
        "$ WHERE item #0 operand #0" + " operand #1".repeat(depth),
        failure.diagnostics().query().expressionPath());
    assertEquals("MAX", failure.diagnostics().querySemantics().aggregateKind());
  }

  @Test
  void longParameterChainsPreserveOrderAndDoNotLeakDependenciesBetweenClauseExpressions() {
    TestTable table = new TestTable("t");
    int parameterCount = 64;
    SqlExpression<Integer> expression = table.id;
    for (int index = 0; index < parameterCount; index++) {
      expression = add(expression, new ParameterSlot<>(index, Integer.class, false));
    }
    SqlPredicate where =
        table.amount.eq(new ParameterSlot<>(parameterCount, Integer.class, false));
    List<SqlExpression<?>> selections = List.of(expression, LiteralExpression.one(Integer.class));
    SelectStatement grouped =
        statement(
            FromClause.of(table),
            selections,
            List.of(table.id),
            where,
            null,
            List.of(order(expression)),
            List.of());
    QueryBlockAnalysis analysis = valid(grouped);
    ResolvedExpression selected = analysis.expressions().getFirst();

    assertEquals(1, selected.columnDependencies().size());
    assertEquals(parameterCount, selected.parameterDependencies().size());
    for (int index = 0; index < parameterCount; index++) {
      assertEquals(index, selected.parameterDependencies().get(index).parameterOrdinal());
    }
    ResolvedExpression constant = analysis.expressions().get(1);
    assertTrue(constant.columnDependencies().isEmpty());
    assertTrue(constant.parameterDependencies().isEmpty());
    ResolvedExpression predicate = analysis.expressions().get(2);
    assertEquals(1, predicate.parameterDependencies().size());
    assertEquals(parameterCount, predicate.parameterDependencies().getFirst().parameterOrdinal());
    assertEquals(
        AMOUNT.ordinal(),
        ((ResolvedColumnIdentity) predicate.columnDependencies().getFirst()).propertyOrdinal());
    assertTrue(analysis.expressions().get(3).parameterDependencies().isEmpty());
    assertEquals(
        selected.parameterDependencies(), analysis.expressions().get(4).parameterDependencies());
    QueryBlockAnalysis ungrouped = valid(new SelectStatement(selections, table, where));
    assertEquals(
        selected.parameterDependencies(),
        ungrouped.expressions().getFirst().parameterDependencies());
    assertEquals(
        selected.columnDependencies(), ungrouped.expressions().getFirst().columnDependencies());
  }

  @Test
  void longColumnChainsPreserveEverySourceOccurrenceAndIndividualGroupingKey() {
    TestTable root = new TestTable("t0");
    List<JoinClause> joins = new ArrayList<>();
    List<SqlExpression<?>> keys = new ArrayList<>();
    keys.add(root.id);
    SqlExpression<Integer> expression = root.id;
    int sourceCount = 33;
    for (int index = 1; index < sourceCount; index++) {
      TestTable joined = new TestTable("t" + index);
      joins.add(new JoinClause(JoinType.CROSS, joined, null));
      keys.add(joined.id);
      expression = add(expression, joined.id);
    }
    FromClause from = new FromClause(root, joins);
    QueryBlockAnalysis analysis =
        valid(statement(from, List.of(expression), keys, null, null, List.of(), List.of()));
    ResolvedExpression selected = analysis.expressions().getFirst();

    assertEquals(sourceCount, selected.columnDependencies().size());
    assertTrue(selected.parameterDependencies().isEmpty());
    for (int index = 0; index < sourceCount; index++) {
      ResolvedColumnIdentity column =
          (ResolvedColumnIdentity) selected.columnDependencies().get(index);
      assertEquals(index, column.source().occurrenceOrdinal());
      assertEquals(ID.ordinal(), column.propertyOrdinal());
      ResolvedExpression key = analysis.expressions().get(index + 1);
      assertEquals(List.of(column), key.columnDependencies());
      assertTrue(key.parameterDependencies().isEmpty());
    }
    QueryBlockAnalysis ungrouped = valid(new SelectStatement(List.of(expression), from));
    assertEquals(
        selected.columnDependencies(), ungrouped.expressions().getFirst().columnDependencies());
  }

  private static ArithmeticExpression<Integer> add(
      SqlExpression<Integer> left, SqlExpression<Integer> right) {
    return new ArithmeticExpression<>(left, ArithmeticOperator.ADD, right);
  }

  private static ScalarSubqueryExpression<Long> countWhere(
      TestTable table, @Nullable SqlPredicate predicate) {
    return new ScalarSubqueryExpression<>(
        new SelectStatement(List.of(AggregateExpression.countAll()), table, predicate));
  }

  private static OrderByItem order(SqlExpression<?> expression) {
    return new OrderByItem(expression, OrderDirection.ASC, NullOrder.DIALECT_DEFAULT);
  }

  private static SelectStatement grouped(
      TestTable table,
      List<? extends SqlExpression<?>> selections,
      List<? extends SqlExpression<?>> keys) {
    return statement(FromClause.of(table), selections, keys, null, null, List.of(), List.of());
  }

  private static SelectStatement statement(
      FromClause from,
      List<? extends SqlExpression<?>> selections,
      List<? extends SqlExpression<?>> keys,
      @Nullable SqlPredicate where,
      @Nullable SqlPredicate having,
      List<OrderByItem> order,
      List<HiddenSelection> hidden) {
    return new SelectStatement(false, selections, hidden, from, where, keys, having, order, null);
  }

  private static QueryBlockAnalysis valid(SelectStatement statement) {
    return assertDoesNotThrow(() -> SemanticValidator.analyzeComplete(statement));
  }

  private static QueryValidationException grouping(
      SelectStatement statement, QuerySemanticReason reason, QueryClause clause) {
    return failure(statement, SkisErrorCode.QUERY_GROUPING_CONTRACT, reason, clause);
  }

  private static QueryValidationException aggregate(
      SelectStatement statement, QuerySemanticReason reason, QueryClause clause) {
    QueryValidationException failure =
        failure(statement, SkisErrorCode.QUERY_AGGREGATE_CONTRACT, reason, clause);
    assertNotNull(failure.diagnostics().querySemantics().aggregateKind());
    assertNotNull(failure.diagnostics().querySemantics().aggregateBlockPath());
    return failure;
  }

  private static QueryValidationException failure(
      SelectStatement statement,
      SkisErrorCode code,
      QuerySemanticReason reason,
      QueryClause clause) {
    QueryValidationException failure =
        assertThrows(
            QueryValidationException.class, () -> SemanticValidator.validateComplete(statement));
    assertEquals(code, failure.code());
    assertEquals(code.defaultStage(), failure.diagnostics().stage());
    assertEquals(reason, failure.diagnostics().querySemantics().reason());
    assertEquals(clause.displayName(), failure.diagnostics().query().clause());
    assertNotNull(failure.diagnostics().query().blockPath());
    assertNotNull(failure.diagnostics().query().expressionPath());
    assertFalse(failure.diagnostics().facts().detail().isBlank());
    assertNull(failure.getCause());
    return failure;
  }

  private record Row(
      Integer id, @Nullable Integer category, @Nullable Integer amount, @Nullable String name) {}

  private static final class TestTable extends TableExpression<Row> {
    private final ColumnExpression<Row, Integer> id = column(ID);
    private final ColumnExpression<Row, Integer> category = column(CATEGORY);
    private final ColumnExpression<Row, Integer> amount = column(AMOUNT);
    private final ColumnExpression<Row, String> name = column(NAME);

    private TestTable(String alias) {
      this(Identifier.of(alias));
    }

    private TestTable(Identifier alias) {
      super(MODEL, alias);
    }

    @Override
    public TestTable as(Identifier alias) {
      return new TestTable(alias);
    }
  }
}
