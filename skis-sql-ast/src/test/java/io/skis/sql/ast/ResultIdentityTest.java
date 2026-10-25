package io.skis.sql.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.QueryValidationException;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResultIdentityTest {

  private static final PropertyMeta<Row, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Row, String> KEY =
      new PropertyMeta<>(1, "key", String.class, ColumnMeta.of("group_key", true));
  private static final EntityMeta<Row> META = EntityMeta.simple(Row.class,
      new TableMeta("", "", "rows"), List.of(ID, KEY),
      new PrimaryKeyMeta<>(List.of(ID)), false);

  @Test
  void completeAnalysisAllowsUnorderedSingletonOffsetButRejectsOrdinaryOffset() {
    var table = new Rows();
    var pagination = new OffsetLimit(new ParameterSlot<>(0, Integer.class, false),
        new ParameterSlot<>(1, Long.class, false));
    var singleton = new SelectStatement(true, List.of(AggregateExpression.countAll()), List.of(),
        table, null, List.of(), null, List.of(), pagination);
    var analysis = SemanticValidator.analyzeComplete(singleton);
    assertTrue(ResultIdentity.resolve(singleton, analysis).atMostOneRow());
    analysis.validateOffsetOrdering(singleton);
    var ordinary = new SelectStatement(false, List.of(table.id), List.of(), table,
        null, List.of(), pagination);
    assertThrows(IllegalArgumentException.class, () -> SemanticValidator.analyzeComplete(ordinary));
  }

  @Test
  void groupingIgnoresRowIndependentKeysAndKeepsNullableKeyIdentity() {
    var table = new Rows();
    var grouped = new SelectStatement(false, List.of(AggregateExpression.countAll()), List.of(),
        table, null, List.of(LiteralExpression.one(Integer.class), table.key), null,
        List.of(order(table.key)), null);
    var analysis = SemanticValidator.analyzeComplete(grouped);
    assertFalse(ResultIdentity.resolve(grouped, analysis).atMostOneRow());
    analysis.validateOffsetOrdering(grouped);
    var constant = new SelectStatement(false, List.of(AggregateExpression.countAll()), List.of(),
        table, null, List.of(LiteralExpression.nullLiteral(String.class)), null, List.of(), null);
    var constantAnalysis = SemanticValidator.analyzeComplete(constant);
    assertTrue(ResultIdentity.resolve(constant, constantAnalysis).atMostOneRow());
    constantAnalysis.validateOffsetOrdering(constant);
  }

  @Test
  void singletonSourcesAndDistinctRowIndependentOutputsTakePrecedenceOverTupleOrdering() {
    var table = new Rows();
    var count = new SelectStatement(List.of(AggregateExpression.countAll()), table);
    var reference = new DerivedRelationReference(Identifier.of("singleton"), List.of(
        new DerivedOutputColumn(Identifier.of("count"), Long.class, SqlType.BIGINT, Nullability.NON_NULL)));
    var value = new DerivedColumnExpression<Long>(reference, 0);
    var outer = new SelectStatement(true, List.of(value), List.of(),
        new FromClause(new DerivedRelationSource(count, reference), List.of()), null,
        List.of(value), null, List.of(), null);
    var analysis = SemanticValidator.analyzeComplete(outer);
    assertTrue(analysis.atMostOneRow());
    analysis.validateOffsetOrdering(outer);
    var constant = new SelectStatement(true, List.of(LiteralExpression.one(Integer.class)),
        List.of(), table, null, List.of(), null);
    var constantAnalysis = SemanticValidator.analyzeComplete(constant);
    assertTrue(constantAnalysis.atMostOneRow());
    constantAnalysis.validateOffsetOrdering(constant);
  }

  @Test
  void groupedDistinctRowIndependentOutputsAllowUnorderedOffsetWithoutFoldingAggregates() {
    var table = new Rows();
    for (SqlExpression<Long> output : List.<SqlExpression<Long>>of(
        LiteralExpression.one(Long.class), new ParameterSlot<>(0, Long.class, false))) {
      var query = new SelectStatement(true, List.of(output), List.of(), table, null,
          List.of(table.key), null, List.of(), null);
      var analysis = SemanticValidator.analyzeComplete(query);
      assertTrue(analysis.atMostOneRow());
      analysis.validateOffsetOrdering(query);
    }
    var parameter = new ParameterSlot<>(0, Long.class, false);
    var paginated = new SelectStatement(true, List.of(parameter), List.of(), table, null,
        List.of(table.key), null, List.of(),
        new OffsetLimit(new ParameterSlot<>(1, Integer.class, false),
            new ParameterSlot<>(2, Long.class, false)));
    SemanticValidator.analyzeComplete(paginated).validateOffsetOrdering(paginated);
    for (SqlExpression<Long> aggregate : List.<SqlExpression<Long>>of(
        AggregateExpression.countAll(),
        new CoalesceExpression<>(List.of(AggregateExpression.countAll(), LiteralExpression.one(Long.class))))) {
      var query = new SelectStatement(true, List.of(aggregate), List.of(), table, null,
          List.of(table.key), null, List.of(), null);
      var analysis = SemanticValidator.analyzeComplete(query);
      assertFalse(analysis.atMostOneRow());
      assertThrows(QueryValidationException.class, () -> analysis.validateOffsetOrdering(query));
    }
  }

  @Test
  void fullJoinCannotUseTwoEntirelyNullableDerivedIdentitiesWithoutPresence() {
    var table = new Rows();
    var left = grouped(table, "left_groups", false);
    var right = grouped(table, "right_groups", false);
    var leftKey = new DerivedColumnExpression<String>(left.reference(), 0);
    var rightKey = new DerivedColumnExpression<String>(right.reference(), 0);
    var query = new SelectStatement(false, List.of(leftKey), List.of(),
        new FromClause(left, List.of(new JoinClause(JoinType.FULL, right,
            new ComparisonPredicate<>(leftKey, ComparisonOperator.EQUAL, rightKey)))),
        null, List.of(), null, List.of(order(leftKey), order(rightKey)), null);
    var analysis = SemanticValidator.analyzeComplete(query);
    assertThrows(QueryValidationException.class, () -> analysis.validateOffsetOrdering(query));
    // Matching NULL keys are a valid group identity in an ordinary inner join.
    var inner = new SelectStatement(false, query.selections(), List.of(),
        new FromClause(left, List.of(new JoinClause(JoinType.INNER, right,
            new ComparisonPredicate<>(leftKey, ComparisonOperator.EQUAL, rightKey)))),
        null, List.of(), null, query.orderBy(), null);
    SemanticValidator.analyzeComplete(inner).validateOffsetOrdering(inner);
  }

  @Test
  void fullJoinUsesAnExplicitlyExportedNonNullPresenceComponentWhenOrdered() {
    var table = new Rows();
    var left = grouped(table, "left_groups", true);
    var right = grouped(table, "right_groups", false);
    var leftKey = new DerivedColumnExpression<String>(left.reference(), 0);
    var rightKey = new DerivedColumnExpression<String>(right.reference(), 0);
    var presence = new DerivedColumnExpression<Long>(left.reference(), 1);
    var from = new FromClause(left, List.of(new JoinClause(JoinType.FULL, right,
        new ComparisonPredicate<>(leftKey, ComparisonOperator.EQUAL, rightKey))));
    var incomplete = new SelectStatement(false, List.of(leftKey), List.of(), from,
        null, List.of(), null, List.of(order(leftKey), order(rightKey)), null);
    assertThrows(QueryValidationException.class,
        () -> SemanticValidator.analyzeComplete(incomplete).validateOffsetOrdering(incomplete));
    var complete = new SelectStatement(false, incomplete.selections(), List.of(), from,
        null, List.of(), null, List.of(order(leftKey), order(rightKey), order(presence)), null);
    SemanticValidator.analyzeComplete(complete).validateOffsetOrdering(complete);
  }

  @Test
  void distinctScalarOrderingUsesValidatedOutputsWithoutMergingIndependentParameters() {
    var table = new Rows(Identifier.of("outer_rows"));
    var inner = new Rows(Identifier.of("inner_rows"));
    var scalar = new ScalarSubqueryExpression<>(new SelectStatement(List.of(inner.id), inner,
        new ComparisonPredicate<>(inner.id, ComparisonOperator.EQUAL, table.id)));
    var query = new SelectStatement(true, List.of(scalar), List.of(), table,
        null, List.of(order(scalar)), null);
    var analysis = SemanticValidator.analyzeComplete(query);
    assertFalse(analysis.atMostOneRow());
    analysis.validateOffsetOrdering(query);

    var first = new ScalarSubqueryExpression<>(new SelectStatement(
        List.of(new ParameterSlot<>(0, Long.class, false)), inner,
        new ComparisonPredicate<>(inner.id, ComparisonOperator.EQUAL, table.id)));
    var repeated = new SelectStatement(true, List.of(first), List.of(), table,
        null, List.of(order(first)), null);
    SemanticValidator.analyzeComplete(repeated).validateOffsetOrdering(repeated);
    var second = new ScalarSubqueryExpression<>(new SelectStatement(
        List.of(new ParameterSlot<>(1, Long.class, false)), inner,
        new ComparisonPredicate<>(inner.id, ComparisonOperator.EQUAL, table.id)));
    var independent = new SelectStatement(true, List.of(first), List.of(), table,
        null, List.of(order(second)), null);
    assertThrows(QueryValidationException.class,
        () -> SemanticValidator.analyzeComplete(independent).validateOffsetOrdering(independent));
  }

  @Test
  void repeatedDistinctScalarOutputsAcceptEitherAliasAndSingleOutputReexport() {
    var table = new Rows(Identifier.of("outer_rows"));
    var inner = new Rows(Identifier.of("inner_rows"));
    var scalar = new ScalarSubqueryExpression<Long>(new SelectStatement(List.of(inner.id), inner,
        new ComparisonPredicate<>(inner.id, ComparisonOperator.EQUAL, table.id)));
    for (SqlExpression<Long> expression : List.<SqlExpression<Long>>of(scalar,
        new CoalesceExpression<>(List.of(scalar, LiteralExpression.one(Long.class))))) {
      var distinct = new SelectStatement(true, List.of(expression, expression), List.of(), table,
          null, List.of(), null);
      var reference = new DerivedRelationReference(Identifier.of("repeated_scalars"), List.of(
          new DerivedOutputColumn(Identifier.of("first"), Long.class, SqlType.BIGINT, Nullability.NULLABLE),
          new DerivedOutputColumn(Identifier.of("second"), Long.class, SqlType.BIGINT, Nullability.NULLABLE)));
      var first = new DerivedColumnExpression<Long>(reference, 0);
      var second = new DerivedColumnExpression<Long>(reference, 1);
      var from = new FromClause(new DerivedRelationSource(distinct, reference), List.of());
      for (var ordered : List.of(first, second)) {
        var query = new SelectStatement(false, List.of(second), List.of(), from, null,
            List.of(), null, List.of(order(ordered)), null);
        SemanticValidator.analyzeComplete(query).validateOffsetOrdering(query);
      }
      var reexport = new SelectStatement(false, List.of(second), List.of(), from, null,
          List.of(), null, List.of(), null);
      var outerReference = new DerivedRelationReference(Identifier.of("reexported_scalar"), List.of(
          new DerivedOutputColumn(Identifier.of("value"), Long.class, SqlType.BIGINT, Nullability.NULLABLE)));
      var value = new DerivedColumnExpression<Long>(outerReference, 0);
      var outer = new SelectStatement(false, List.of(value), List.of(),
          new FromClause(new DerivedRelationSource(reexport, outerReference), List.of()), null,
          List.of(), null, List.of(order(value)), null);
      SemanticValidator.analyzeComplete(outer).validateOffsetOrdering(outer);
    }
  }

  @Test
  void distinctScalarOutputsWithIndependentParameterSlotsStillRequireBothAliases() {
    var table = new Rows(Identifier.of("outer_rows"));
    var inner = new Rows(Identifier.of("inner_rows"));
    var predicate = new ComparisonPredicate<>(inner.id, ComparisonOperator.EQUAL, table.id);
    var firstScalar = new ScalarSubqueryExpression<Long>(new SelectStatement(
        List.of(new ParameterSlot<>(0, Long.class, false)), inner, predicate));
    var secondScalar = new ScalarSubqueryExpression<Long>(new SelectStatement(
        List.of(new ParameterSlot<>(1, Long.class, false)), inner, predicate));
    var distinct = new SelectStatement(true, List.of(firstScalar, secondScalar), List.of(), table,
        null, List.of(), null);
    var reference = new DerivedRelationReference(Identifier.of("independent_scalars"), List.of(
        new DerivedOutputColumn(Identifier.of("first"), Long.class, SqlType.BIGINT, Nullability.NULLABLE),
        new DerivedOutputColumn(Identifier.of("second"), Long.class, SqlType.BIGINT, Nullability.NULLABLE)));
    var first = new DerivedColumnExpression<Long>(reference, 0);
    var second = new DerivedColumnExpression<Long>(reference, 1);
    var from = new FromClause(new DerivedRelationSource(distinct, reference), List.of());
    var incomplete = new SelectStatement(false, List.of(second), List.of(), from, null,
        List.of(), null, List.of(order(second)), null);
    assertThrows(QueryValidationException.class,
        () -> SemanticValidator.analyzeComplete(incomplete).validateOffsetOrdering(incomplete));
    var complete = new SelectStatement(false, List.of(second), List.of(), from, null,
        List.of(), null, List.of(order(first), order(second)), null);
    SemanticValidator.analyzeComplete(complete).validateOffsetOrdering(complete);
  }

  @Test
  void fullJoinAcceptsEitherSideAndEveryExportedPresenceCandidate() {
    var table = new Rows();
    var left = grouped(table, "left_groups", true);
    var right = grouped(table, "right_groups", true);
    var leftKey = new DerivedColumnExpression<String>(left.reference(), 0);
    var rightKey = new DerivedColumnExpression<String>(right.reference(), 0);
    var from = new FromClause(left, List.of(new JoinClause(JoinType.FULL, right,
        new ComparisonPredicate<>(leftKey, ComparisonOperator.EQUAL, rightKey))));
    for (var source : List.of(left, right)) {
      var presence = new DerivedColumnExpression<Long>(source.reference(), 1);
      var query = new SelectStatement(false, List.of(leftKey), List.of(), from,
          null, List.of(), null, List.of(order(leftKey), order(rightKey), order(presence)), null);
      SemanticValidator.analyzeComplete(query).validateOffsetOrdering(query);
    }

    var extra = new SelectStatement(false,
        List.of(table.key, AggregateExpression.countAll(), LiteralExpression.one(Long.class)),
        List.of(), table, null, List.of(table.key), null, List.of(), null);
    var reference = new DerivedRelationReference(Identifier.of("extra_presence"), List.of(
        new DerivedOutputColumn(Identifier.of("key"), String.class, SqlType.VARCHAR, Nullability.NULLABLE),
        new DerivedOutputColumn(Identifier.of("count"), Long.class, SqlType.BIGINT, Nullability.NON_NULL),
        new DerivedOutputColumn(Identifier.of("present"), Long.class, SqlType.BIGINT, Nullability.NON_NULL)));
    var extraKey = new DerivedColumnExpression<String>(reference, 0);
    var secondPresence = new DerivedColumnExpression<Long>(reference, 2);
    var extraFrom = new FromClause(new DerivedRelationSource(extra, reference),
        List.of(new JoinClause(JoinType.FULL, right,
            new ComparisonPredicate<>(extraKey, ComparisonOperator.EQUAL, rightKey))));
    var query = new SelectStatement(false, List.of(extraKey), List.of(), extraFrom,
        null, List.of(), null, List.of(order(extraKey), order(rightKey), order(secondPresence)), null);
    SemanticValidator.analyzeComplete(query).validateOffsetOrdering(query);
  }

  @Test
  void derivedFullJoinCanExportOnlyTheRightPresenceAlternative() {
    var table = new Rows();
    var left = grouped(table, "left_groups", true);
    var right = grouped(table, "right_groups", true);
    var leftKey = new DerivedColumnExpression<String>(left.reference(), 0);
    var rightKey = new DerivedColumnExpression<String>(right.reference(), 0);
    var rightPresence = new DerivedColumnExpression<Long>(right.reference(), 1);
    var inner = new SelectStatement(false, List.of(leftKey, rightKey, rightPresence), List.of(),
        new FromClause(left, List.of(new JoinClause(JoinType.FULL, right,
            new ComparisonPredicate<>(leftKey, ComparisonOperator.EQUAL, rightKey)))),
        null, List.of(), null, List.of(), null);
    var reference = new DerivedRelationReference(Identifier.of("joined_groups"), List.of(
        new DerivedOutputColumn(Identifier.of("left_key"), String.class, SqlType.VARCHAR, Nullability.NULLABLE),
        new DerivedOutputColumn(Identifier.of("right_key"), String.class, SqlType.VARCHAR, Nullability.NULLABLE),
        new DerivedOutputColumn(Identifier.of("present"), Long.class, SqlType.BIGINT, Nullability.NULLABLE)));
    var outerLeft = new DerivedColumnExpression<String>(reference, 0);
    var outerRight = new DerivedColumnExpression<String>(reference, 1);
    var outerPresence = new DerivedColumnExpression<Long>(reference, 2);
    var from = new FromClause(new DerivedRelationSource(inner, reference), List.of());
    var incomplete = new SelectStatement(false, List.of(outerLeft), List.of(), from,
        null, List.of(), null, List.of(order(outerLeft), order(outerRight)), null);
    assertThrows(QueryValidationException.class,
        () -> SemanticValidator.analyzeComplete(incomplete).validateOffsetOrdering(incomplete));
    var complete = new SelectStatement(false, incomplete.selections(), List.of(), from,
        null, List.of(), null, List.of(order(outerLeft), order(outerRight), order(outerPresence)), null);
    SemanticValidator.analyzeComplete(complete).validateOffsetOrdering(complete);
  }

  @Test
  void fullJoinOfSingletonsStillNeedsAnOrderedExistenceComponent() {
    var table = new Rows();
    var count = new SelectStatement(List.of(AggregateExpression.countAll()), table);
    var leftReference = new DerivedRelationReference(Identifier.of("left_count"), List.of(
        new DerivedOutputColumn(Identifier.of("count"), Long.class, SqlType.BIGINT, Nullability.NON_NULL)));
    var rightReference = new DerivedRelationReference(Identifier.of("right_count"), leftReference.outputs());
    var left = new DerivedColumnExpression<Long>(leftReference, 0);
    var right = new DerivedColumnExpression<Long>(rightReference, 0);
    var from = new FromClause(new DerivedRelationSource(count, leftReference), List.of(
        new JoinClause(JoinType.FULL, new DerivedRelationSource(count, rightReference),
            new ComparisonPredicate<>(left, ComparisonOperator.NOT_EQUAL, right))));
    var unordered = new SelectStatement(false, List.of(left), List.of(), from,
        null, List.of(), null, List.of(), null);
    var analysis = SemanticValidator.analyzeComplete(unordered);
    assertFalse(ResultIdentity.resolve(unordered, analysis).atMostOneRow());
    assertThrows(QueryValidationException.class, () -> analysis.validateOffsetOrdering(unordered));
    var ordered = new SelectStatement(false, unordered.selections(), List.of(), from,
        null, List.of(), null, List.of(order(right)), null);
    SemanticValidator.analyzeComplete(ordered).validateOffsetOrdering(ordered);
  }

  @Test
  void aNullableDerivedGroupDoesNotRequireAnUnneededPresenceOrderingComponent() {
    var table = new Rows();
    var grouped = grouped(table, "groups", true);
    var key = new DerivedColumnExpression<String>(grouped.reference(), 0);
    var query = new SelectStatement(false, List.of(key), List.of(),
        new FromClause(grouped, List.of()), null, List.of(), null, List.of(order(key)), null);
    SemanticValidator.analyzeComplete(query).validateOffsetOrdering(query);
  }

  @Test
  void repeatedIdentityExportsAcceptEitherAliasAndStillRequireTheCompleteTuple() {
    var table = new Rows();
    var inner = new SelectStatement(false, List.of(table.id, table.id, table.key), List.of(),
        table, null, List.of(table.id, table.key), null, List.of(), null);
    var reference = new DerivedRelationReference(Identifier.of("repeated_keys"), List.of(
        new DerivedOutputColumn(Identifier.of("first_id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL),
        new DerivedOutputColumn(Identifier.of("second_id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL),
        new DerivedOutputColumn(Identifier.of("key"), String.class, SqlType.VARCHAR, Nullability.NULLABLE)));
    var first = new DerivedColumnExpression<Long>(reference, 0);
    var second = new DerivedColumnExpression<Long>(reference, 1);
    var key = new DerivedColumnExpression<String>(reference, 2);
    var from = new FromClause(new DerivedRelationSource(inner, reference), List.of());
    for (var id : List.of(first, second)) {
      var complete = new SelectStatement(false, List.of(second), List.of(), from, null,
          List.of(), null, List.of(order(id), order(key)), null);
      SemanticValidator.analyzeComplete(complete).validateOffsetOrdering(complete);
    }
    var incomplete = new SelectStatement(false, List.of(second), List.of(), from, null,
        List.of(), null, List.of(order(second)), null);
    assertThrows(QueryValidationException.class,
        () -> SemanticValidator.analyzeComplete(incomplete).validateOffsetOrdering(incomplete));

    var reexport = new SelectStatement(false, List.of(second, key), List.of(), from,
        null, List.of(), null, List.of(), null);
    var outerReference = new DerivedRelationReference(Identifier.of("reexported_keys"), List.of(
        new DerivedOutputColumn(Identifier.of("id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL),
        new DerivedOutputColumn(Identifier.of("key"), String.class, SqlType.VARCHAR, Nullability.NULLABLE)));
    var outerId = new DerivedColumnExpression<Long>(outerReference, 0);
    var outerKey = new DerivedColumnExpression<String>(outerReference, 1);
    var outer = new SelectStatement(false, List.of(outerId), List.of(),
        new FromClause(new DerivedRelationSource(reexport, outerReference), List.of()),
        null, List.of(), null, List.of(order(outerId), order(outerKey)), null);
    SemanticValidator.analyzeComplete(outer).validateOffsetOrdering(outer);
  }

  @Test
  void repeatedPresenceExportsRemainAlternativesThroughAnotherDerivedLayer() {
    var table = new Rows();
    var count = AggregateExpression.countAll();
    var inner = new SelectStatement(false, List.of(table.key, count, count), List.of(), table,
        null, List.of(table.key), null, List.of(), null);
    var reference = new DerivedRelationReference(Identifier.of("repeated_presence"), List.of(
        new DerivedOutputColumn(Identifier.of("key"), String.class, SqlType.VARCHAR, Nullability.NULLABLE),
        new DerivedOutputColumn(Identifier.of("first_count"), Long.class, SqlType.BIGINT, Nullability.NON_NULL),
        new DerivedOutputColumn(Identifier.of("second_count"), Long.class, SqlType.BIGINT, Nullability.NON_NULL)));
    var leftKey = new DerivedColumnExpression<String>(reference, 0);
    var secondCount = new DerivedColumnExpression<Long>(reference, 2);
    var right = grouped(table, "other_groups", false);
    var rightKey = new DerivedColumnExpression<String>(right.reference(), 0);
    var from = new FromClause(new DerivedRelationSource(inner, reference), List.of(
        new JoinClause(JoinType.FULL, right,
            new ComparisonPredicate<>(leftKey, ComparisonOperator.EQUAL, rightKey))));
    var joined = new SelectStatement(false, List.of(leftKey, rightKey, secondCount), List.of(),
        from, null, List.of(), null,
        List.of(order(leftKey), order(rightKey), order(secondCount)), null);
    SemanticValidator.analyzeComplete(joined).validateOffsetOrdering(joined);

    var outerReference = new DerivedRelationReference(Identifier.of("presence_export"), List.of(
        new DerivedOutputColumn(Identifier.of("left_key"), String.class, SqlType.VARCHAR, Nullability.NULLABLE),
        new DerivedOutputColumn(Identifier.of("right_key"), String.class, SqlType.VARCHAR, Nullability.NULLABLE),
        new DerivedOutputColumn(Identifier.of("count"), Long.class, SqlType.BIGINT, Nullability.NULLABLE)));
    var outerLeft = new DerivedColumnExpression<String>(outerReference, 0);
    var outerRight = new DerivedColumnExpression<String>(outerReference, 1);
    var outerCount = new DerivedColumnExpression<Long>(outerReference, 2);
    var outerFrom = new FromClause(new DerivedRelationSource(joined, outerReference), List.of());
    var incomplete = new SelectStatement(false, List.of(outerLeft), List.of(), outerFrom,
        null, List.of(), null, List.of(order(outerLeft), order(outerRight)), null);
    assertThrows(QueryValidationException.class,
        () -> SemanticValidator.analyzeComplete(incomplete).validateOffsetOrdering(incomplete));
    var complete = new SelectStatement(false, incomplete.selections(), List.of(), outerFrom,
        null, List.of(), null, List.of(order(outerLeft), order(outerRight), order(outerCount)), null);
    SemanticValidator.analyzeComplete(complete).validateOffsetOrdering(complete);
  }

  @Test
  void derivedFullJoinPreservesTheNonAllNullIdentityTuple() {
    var left = new Rows(Identifier.of("left_rows"));
    var right = new Rows(Identifier.of("right_rows"));
    var inner = new SelectStatement(false, List.of(left.id, right.id), List.of(),
        new FromClause(left, List.of(new JoinClause(JoinType.FULL, right,
            new ComparisonPredicate<>(left.id, ComparisonOperator.EQUAL, right.id)))),
        null, List.of(), null, List.of(), null);
    var reference = new DerivedRelationReference(Identifier.of("joined_rows"), List.of(
        new DerivedOutputColumn(Identifier.of("left_id"), Long.class, SqlType.BIGINT, Nullability.NULLABLE),
        new DerivedOutputColumn(Identifier.of("right_id"), Long.class, SqlType.BIGINT, Nullability.NULLABLE)));
    var leftId = new DerivedColumnExpression<Long>(reference, 0);
    var rightId = new DerivedColumnExpression<Long>(reference, 1);
    var groups = grouped(left, "nullable_groups", false);
    var key = new DerivedColumnExpression<String>(groups.reference(), 0);
    var from = new FromClause(new DerivedRelationSource(inner, reference), List.of(
        new JoinClause(JoinType.FULL, groups, new BooleanPredicate(false))));
    var complete = new SelectStatement(false, List.of(leftId), List.of(), from,
        null, List.of(), null, List.of(order(leftId), order(rightId), order(key)), null);
    SemanticValidator.analyzeComplete(complete).validateOffsetOrdering(complete);
    var incomplete = new SelectStatement(false, complete.selections(), List.of(), from,
        null, List.of(), null, List.of(order(leftId), order(key)), null);
    assertThrows(QueryValidationException.class,
        () -> SemanticValidator.analyzeComplete(incomplete).validateOffsetOrdering(incomplete));
  }

  @Test
  void distinctPreservesRepeatedDerivedAliasesThroughOrderingAndReexport() {
    var table = new Rows();
    // The second shape deliberately omits part of its source identity. DISTINCT proves its
    // own visible tuple, while raw output equality remains valid for both source shapes.
    for (List<SqlExpression<?>> groups : List.of(
        List.<SqlExpression<?>>of(table.key), List.<SqlExpression<?>>of(table.id, table.key))) {
      var inner = new SelectStatement(false, List.of(table.key, table.key), List.of(), table,
          null, groups, null, List.of(), null);
      var reference = new DerivedRelationReference(Identifier.of("duplicate_keys"), List.of(
          new DerivedOutputColumn(Identifier.of("first"), String.class, SqlType.VARCHAR, Nullability.NULLABLE),
          new DerivedOutputColumn(Identifier.of("second"), String.class, SqlType.VARCHAR, Nullability.NULLABLE)));
      var first = new DerivedColumnExpression<String>(reference, 0);
      var second = new DerivedColumnExpression<String>(reference, 1);
      var from = FromClause.of(new DerivedRelationSource(inner, reference));
      for (var ordered : List.of(first, second)) {
        var distinct = new SelectStatement(true, List.of(first, second), List.of(), from,
            null, List.of(), null, List.of(order(ordered)), null);
        var analysis = SemanticValidator.analyzeComplete(distinct);
        assertEquals(List.of(0, 0), analysis.distinctOutputRepresentatives(distinct));
        analysis.validateOffsetOrdering(distinct);

        var reexport = new DerivedRelationReference(Identifier.of("distinct_keys"), reference.outputs());
        var reexportedSecond = new DerivedColumnExpression<String>(reexport, 1);
        var middle = new SelectStatement(false, List.of(reexportedSecond), List.of(),
            FromClause.of(new DerivedRelationSource(distinct, reexport)), null, List.of(), null,
            List.of(), null);
        var outerReference = new DerivedRelationReference(Identifier.of("one_key"), List.of(
            new DerivedOutputColumn(Identifier.of("key"), String.class, SqlType.VARCHAR, Nullability.NULLABLE)));
        var value = new DerivedColumnExpression<String>(outerReference, 0);
        var outer = new SelectStatement(false, List.of(value), List.of(),
            FromClause.of(new DerivedRelationSource(middle, outerReference)), null, List.of(), null,
            List.of(order(value)), null);
        SemanticValidator.analyzeComplete(outer).validateOffsetOrdering(outer);
      }
    }
  }

  @Test
  void distinctAliasEquivalenceDoesNotRemoveOtherOutputsOrMergeSources() {
    var table = new Rows();
    var inner = new SelectStatement(false, List.of(table.key, table.key, table.id), List.of(),
        table, null, List.of(table.key, table.id), null, List.of(), null);
    var reference = new DerivedRelationReference(Identifier.of("keys_and_id"), List.of(
        new DerivedOutputColumn(Identifier.of("first"), String.class, SqlType.VARCHAR, Nullability.NULLABLE),
        new DerivedOutputColumn(Identifier.of("second"), String.class, SqlType.VARCHAR, Nullability.NULLABLE),
        new DerivedOutputColumn(Identifier.of("id"), Long.class, SqlType.BIGINT, Nullability.NON_NULL)));
    var first = new DerivedColumnExpression<String>(reference, 0);
    var second = new DerivedColumnExpression<String>(reference, 1);
    var id = new DerivedColumnExpression<Long>(reference, 2);
    var from = FromClause.of(new DerivedRelationSource(inner, reference));
    var incomplete = new SelectStatement(true, List.of(first, second, id), List.of(), from,
        null, List.of(), null, List.of(order(second)), null);
    var analysis = SemanticValidator.analyzeComplete(incomplete);
    assertEquals(List.of(0, 0, 2), analysis.distinctOutputRepresentatives(incomplete));
    assertThrows(QueryValidationException.class, () -> analysis.validateOffsetOrdering(incomplete));
    var complete = new SelectStatement(true, incomplete.selections(), List.of(), from,
        null, List.of(), null, List.of(order(second), order(id)), null);
    SemanticValidator.analyzeComplete(complete).validateOffsetOrdering(complete);

    var otherReference = new DerivedRelationReference(Identifier.of("other_keys"), reference.outputs());
    var other = new DerivedColumnExpression<String>(otherReference, 1);
    var joined = new SelectStatement(true, List.of(first, other), List.of(),
        new FromClause(new DerivedRelationSource(inner, reference), List.of(
            new JoinClause(JoinType.CROSS, new DerivedRelationSource(inner, otherReference), null))),
        null, List.of(), null, List.of(order(other)), null);
    var joinedAnalysis = SemanticValidator.analyzeComplete(joined);
    assertEquals(List.of(0, 1), joinedAnalysis.distinctOutputRepresentatives(joined));
    assertThrows(QueryValidationException.class, () -> joinedAnalysis.validateOffsetOrdering(joined));
  }

  private static DerivedRelationSource grouped(Rows table, String alias, boolean presence) {
    List<SqlExpression<?>> selections = presence
        ? List.of(table.key, AggregateExpression.countAll()) : List.of(table.key);
    var statement = new SelectStatement(false, selections, List.of(), table,
        null, List.of(table.key), null, List.of(), null);
    List<DerivedOutputColumn> outputs = presence
        ? List.of(new DerivedOutputColumn(Identifier.of("key"), String.class, SqlType.VARCHAR, Nullability.NULLABLE),
            new DerivedOutputColumn(Identifier.of("present"), Long.class, SqlType.BIGINT, Nullability.NON_NULL))
        : List.of(new DerivedOutputColumn(Identifier.of("key"), String.class, SqlType.VARCHAR, Nullability.NULLABLE));
    return new DerivedRelationSource(statement, new DerivedRelationReference(Identifier.of(alias), outputs));
  }

  private static OrderByItem order(SqlExpression<?> expression) {
    return new OrderByItem(expression, OrderDirection.ASC, NullOrder.DIALECT_DEFAULT);
  }

  private record Row(Long id, String key) {}

  private static final class Rows extends TableExpression<Row> {
    private final ColumnExpression<Row, Long> id = column(ID);
    private final ColumnExpression<Row, String> key = column(KEY);
    private Rows() { super(META); }
    private Rows(Identifier alias) { super(META, alias); }
    @Override public Rows as(Identifier alias) { return new Rows(alias); }
  }
}
