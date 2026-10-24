package io.skis.dialect.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.SkisErrorCode;
import io.skis.dialect.SqlRenderException;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.DialectLowering;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.PortableLowering;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.ArithmeticOperator;
import io.skis.sql.ast.BetweenPredicate;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.CaseWhen;
import io.skis.sql.ast.CoalesceExpression;
import io.skis.sql.ast.ConcatExpression;
import io.skis.sql.ast.DecimalPrecision;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedOutputColumn;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.DerivedRelationSource;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.HiddenSelection;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.OrderDirection;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.ParameterRelationSource;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SelectedOutputReference;
import io.skis.sql.ast.SemanticValidator;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.TableExpression;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Target conversion, grouping identity and fail-closed output transformation contracts. */
class AggregateDialectTest {

  @Test
  void distinctAggregateOrderingReusesOutputsWithTheirEffectiveNullability() {
    NumberTable table = new NumberTable("n");
    var count = AggregateExpression.countAll();
    var sum = AggregateExpression.sumAsLong(table.category);
    List<SqlExpression<?>> outputs = List.of(count, sum,
        AggregateExpression.sumAsDouble(table.real),
        AggregateExpression.sumAsDouble(table.approximate),
        new CastExpression<>(sum, BigDecimal.class),
        new ArithmeticExpression<>(count, ArithmeticOperator.ADD, LiteralExpression.one(Long.class)),
        new CoalesceExpression<>(List.of(sum, LiteralExpression.zero(Long.class))));
    for (String id : List.of("postgresql", "h2")) {
      for (SqlExpression<?> output : outputs) {
        var query = new SelectStatement(true, List.of(output), List.of(), table, null, List.of(), null,
            List.of(new OrderByItem(output, OrderDirection.ASC, NullOrder.LAST)), null);
        SqlTemplate template = compile(id, query);
        assertTrue(template.sql().endsWith("ORDER BY 1 ASC NULLS LAST"), template.sql());
        assertTrue(template.parameterLayout().isEmpty());
      }
    }
  }

  @Test
  void thirdPartyLoweringCanNormalizeAggregateOrderingBeforeThePipeline() {
    NumberTable table = new NumberTable("n");
    for (boolean nativeNullOrdering : List.of(false, true)) {
      EnumSet<DialectFeature> enabled = EnumSet.allOf(DialectFeature.class);
      if (!nativeNullOrdering) {
        enabled.remove(DialectFeature.NULLS_FIRST_LAST);
      }
      DialectCapabilities capabilities =
          DialectCapabilities.of(enabled.toArray(DialectFeature[]::new));
      FixtureDialect identity =
          new FixtureDialect("postgresql", capabilities, DialectLowering.identity());
      FixtureDialect normalized = new FixtureDialect("postgresql", capabilities,
          input -> PortableLowering.normalize(input.statement(), capabilities));
      for (SqlExpression<?> output : List.of(AggregateExpression.countAll(),
          AggregateExpression.sumAsLong(table.category))) {
        var query = new SelectStatement(true, List.of(output), List.of(), table, null, List.of(), null,
            List.of(new OrderByItem(output, OrderDirection.ASC, NullOrder.LAST)), null);
        var nested = new SelectStatement(List.of(new ScalarSubqueryExpression<>(query)),
            new NumberTable("o"));
        for (SelectStatement statement : List.of(query, nested)) {
          SqlTemplate expected = SqlCompiler.compile(statement, identity);
          SqlTemplate actual = SqlCompiler.compile(statement, normalized);
          assertEquals(expected.sql(), actual.sql());
          assertTrue(actual.parameterLayout().isEmpty());
        }
      }
    }
  }

  @Test
  void thirdPartyNormalizationKeepsBooleanGroupingAndDuplicateConstantKeys() {
    NumberTable table = new NumberTable("n");
    List<List<SqlExpression<?>>> groups = List.of(
        List.of(new BooleanPredicate(false)),
        List.of(new BooleanPredicate(true)),
        List.of(new InPredicate<>(table.id, List.of(), false),
            new InPredicate<>(table.otherId, List.of(), false),
            new CastExpression<>(new BooleanPredicate(false), Boolean.class)));
    for (String id : List.of("postgresql", "h2")) {
      DialectLowering standard = aggregateLowering(id);
      var eager = new FixtureDialect(id, allFeatures(), input ->
          PortableLowering.normalize(standard.lower(input), allFeatures()));
      for (List<SqlExpression<?>> keys : groups) {
        var query = grouped(table, List.of(AggregateExpression.countAll()), keys);
        assertEquivalentNormalization(query, id, eager);
        assertEquivalentNormalization(
            new SelectStatement(List.of(new ScalarSubqueryExpression<>(query)), new NumberTable("o")),
            id, eager);
        var reference = new DerivedRelationReference(Identifier.of("d"), List.of(
            new DerivedOutputColumn(Identifier.of("total"), Long.class, SqlType.BIGINT,
                Nullability.NON_NULL)));
        var derived = new SelectStatement(List.of(new DerivedColumnExpression<Long>(reference, 0)),
            new FromClause(new DerivedRelationSource(query, reference), List.of()));
        assertEquivalentNormalization(derived, id, eager);
      }
    }
  }

  @Test
  void thirdPartyNormalizationKeepsPrunedPredicatesAndNumericAggregateWitnesses() {
    NumberTable table = new NumberTable("n");
    List<SqlExpression<?>> aggregates = List.of(AggregateExpression.countAll(),
        AggregateExpression.countDistinct(table.id), AggregateExpression.sumAsLong(table.tiny),
        AggregateExpression.sumAsBigInteger(table.id),
        AggregateExpression.avgAsBigDecimal(table.category),
        AggregateExpression.sumAsDouble(table.real),
        AggregateExpression.sumAsDouble(table.approximate),
        AggregateExpression.avgAsDouble(table.real),
        AggregateExpression.min(table.id), AggregateExpression.max(table.id));
    for (String id : List.of("postgresql", "h2")) {
      DialectLowering standard = aggregateLowering(id);
      var eager = new FixtureDialect(id, allFeatures(), input ->
          PortableLowering.normalize(standard.lower(input), allFeatures()));
      assertEquivalentNormalization(new CountAst(table,
          new InPredicate<>(table.id, List.of(), false), null), id, eager);
      for (SqlExpression<?> aggregate : aggregates) {
        for (boolean negated : List.of(false, true)) {
          var empty = new InPredicate<>(aggregate, List.of(), negated);
          var query = new SelectStatement(List.of(empty), table);
          assertEquivalentNormalization(query, id, eager);
          assertEquivalentNormalization(
              new SelectStatement(List.of(new ScalarSubqueryExpression<>(query)), new NumberTable("o")),
              id, eager);
          var grouped = new SelectStatement(false, List.of(AggregateExpression.countAll()), List.of(),
              table, new InPredicate<>(table.id, List.of(), negated), List.of(table.category),
              empty, List.of(), null);
          assertEquivalentNormalization(grouped, id, eager);
        }
      }
    }
  }

  @Test
  void normalizedConstantsCannotChangeTruthValuesOrRemoveExplicitGrouping() {
    NumberTable table = new NumberTable("n");
    var grouped = grouped(table, List.of(AggregateExpression.countAll()),
        List.of(new BooleanPredicate(false)));
    assertRejected(grouped, input -> grouped(table, grouped.selections(),
        List.of(new CastExpression<>(new BooleanPredicate(true), Boolean.class))));
    assertRejected(grouped, input -> new SelectStatement(grouped.selections(), table));
    var empty = new SelectStatement(List.of(
        new InPredicate<>(AggregateExpression.countAll(), List.of(), false)), table);
    assertRejected(empty, input -> new SelectStatement(List.of(
        AggregateExpression.countAll().emptyMembership(true)), table));
    assertRejected(empty, input -> new SelectStatement(List.of(new BooleanPredicate(false)), table));
    var sum = new SelectStatement(List.of(new InPredicate<>(
        AggregateExpression.sumAsBigDecimal(table.decimal), List.of(), false)), table);
    assertRejected(sum, input -> new SelectStatement(List.of(
        AggregateExpression.avgAsBigDecimal(table.decimal).emptyMembership(false)), table));
  }

  private static void assertEquivalentNormalization(
      StatementAst query, String id, Dialect eager) {
    SqlTemplate expected = compile(id, query);
    SqlTemplate actual = SqlCompiler.compile(query, eager);
    assertEquals(expected.sql(), actual.sql());
    assertEquals(expected.parameterLayout().slots(), actual.parameterLayout().slots());
  }

  @Test
  void normalizedOrderingStillRejectsWrongOutputDirectionAndNullPlacement() {
    NumberTable table = new NumberTable("n");
    var first = AggregateExpression.sumAsBigInteger(table.id);
    var second = AggregateExpression.sumAsBigInteger(table.otherId);
    var query = new SelectStatement(true, List.of(first, second), List.of(), table, null, List.of(), null,
        List.of(new OrderByItem(first, OrderDirection.ASC, NullOrder.LAST)), null);
    var reference = new SelectedOutputReference<>(1, BigInteger.class, SqlType.DECIMAL, Nullability.NULLABLE);
    List<OrderByItem> invalid = List.of(
        new OrderByItem(new SelectedOutputReference<>(2, BigInteger.class, SqlType.DECIMAL, Nullability.NULLABLE),
            OrderDirection.ASC, NullOrder.LAST),
        new OrderByItem(reference, OrderDirection.DESC, NullOrder.LAST),
        new OrderByItem(reference, OrderDirection.ASC, NullOrder.FIRST));
    for (OrderByItem item : invalid) {
      assertRejected(query, input -> new SelectStatement(true, query.selections(), List.of(), table,
          null, List.of(), null, List.of(item), null));
    }
  }

  @Test
  void emulatedOrderingCannotLoseOrChangeItsNullRank() {
    NumberTable table = new NumberTable("n");
    var sum = AggregateExpression.sumAsLong(table.category);
    var query = new SelectStatement(true, List.of(sum), List.of(), table, null, List.of(), null,
        List.of(new OrderByItem(sum, OrderDirection.ASC, NullOrder.LAST)), null);
    EnumSet<DialectFeature> enabled = EnumSet.allOf(DialectFeature.class);
    enabled.remove(DialectFeature.NULLS_FIRST_LAST);
    DialectCapabilities capabilities =
        DialectCapabilities.of(enabled.toArray(DialectFeature[]::new));
    var normalized = (SelectStatement) PortableLowering.normalize(query, capabilities);
    for (List<OrderByItem> ordering : List.of(List.of(normalized.orderBy().getLast()),
        List.of(new OrderByItem(LiteralExpression.one(Integer.class), OrderDirection.ASC,
            NullOrder.DIALECT_DEFAULT), normalized.orderBy().getLast()))) {
      FixtureDialect changed = new FixtureDialect("postgresql", capabilities,
          input -> new SelectStatement(true, query.selections(), List.of(), table, null, List.of(), null,
              ordering, null));
      SqlRenderException failure = assertThrows(SqlRenderException.class,
          () -> SqlCompiler.compile(query, changed));
      assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, failure.code());
    }
  }

  @Test
  void countStatementsRequireBothCountCapabilitiesBeforeLowering() {
    NumberTable table = new NumberTable("n");
    List<StatementAst> statements = List.of(new CountAst(table, null, null),
        new CountAst(table, null, table.id),
        new SelectStatement(List.of(AggregateExpression.countAll()), table),
        new SelectStatement(List.of(AggregateExpression.countDistinct(table.id)), table));
    EnumSet<DialectFeature> enabled = EnumSet.allOf(DialectFeature.class);
    enabled.remove(DialectFeature.COUNT_AGGREGATE);
    FixtureDialect missingCount = new FixtureDialect("postgresql",
        DialectCapabilities.of(enabled.toArray(DialectFeature[]::new)), input -> {
          throw new AssertionError("unsupported COUNT reached lowering");
        });
    for (StatementAst statement : statements) {
      SqlRenderException failure = assertThrows(SqlRenderException.class,
          () -> SqlCompiler.compile(statement, missingCount));
      assertEquals(SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED, failure.code());
      assertEquals("COUNT_AGGREGATE", failure.diagnostics().dialect().missingCapability());
    }
    FixtureDialect countOnly = new FixtureDialect("postgresql",
        DialectCapabilities.of(DialectFeature.COUNT_AGGREGATE), DialectLowering.identity());
    assertEquals("SELECT COUNT(*) FROM \"numbers\" AS \"n\"",
        SqlCompiler.compile(statements.getFirst(), countOnly).sql());
    for (StatementAst statement : List.of(statements.get(1), statements.get(3))) {
      SqlRenderException failure = assertThrows(SqlRenderException.class,
          () -> SqlCompiler.compile(statement, countOnly));
      assertEquals("COUNT_DISTINCT", failure.diagnostics().dialect().missingCapability());
    }
    assertEquals("SELECT COUNT(DISTINCT \"n\".\"id\") FROM \"numbers\" AS \"n\"",
        SqlCompiler.compile(statements.get(1), new FixtureDialect("postgresql",
            DialectCapabilities.of(DialectFeature.COUNT_AGGREGATE, DialectFeature.COUNT_DISTINCT),
            DialectLowering.identity())).sql());
  }

  @Test
  void booleanGroupConstantsAreTypedAfterEmptyMembershipNormalization() {
    NumberTable table = new NumberTable("n");
    for (String id : List.of("postgresql", "h2")) {
      for (boolean value : List.of(false, true)) {
        for (SqlExpression<?> key : List.of(new BooleanPredicate(value),
            new InPredicate<>(table.id, List.of(), value))) {
          var query = grouped(table, List.of(AggregateExpression.countAll()), List.of(key));
          assertEquals("SELECT COUNT(*) FROM \"numbers\" AS \"n\" GROUP BY CAST("
              + (value ? "TRUE" : "FALSE") + " AS BOOLEAN)", compile(id, query).sql());
          StatementAst normalized = PortableLowering.normalize(query, allFeatures());
          assertSame(normalized, PortableLowering.normalize(normalized, allFeatures()));
        }
      }
      var query = grouped(table, List.of(AggregateExpression.countAll()), List.of(
          new InPredicate<>(table.id, List.of(), false),
          new InPredicate<>(table.otherId, List.of(), false),
          new CastExpression<>(new BooleanPredicate(false), Boolean.class)));
      assertEquals("SELECT COUNT(*) FROM \"numbers\" AS \"n\" GROUP BY CAST(FALSE AS BOOLEAN)",
          compile(id, query).sql());
    }
  }

  @Test
  void numericSummariesKeepNativeAggregatesOutOfTheOtherDialectsTraversal() {
    NumberTable table = new NumberTable("n");
    var exact = new SelectStatement(List.of(AggregateExpression.avgAsBigDecimal(table.category)), table);
    var approximate = new SelectStatement(List.of(AggregateExpression.sumAsDouble(table.approximate),
        AggregateExpression.avgAsDouble(table.approximate)), table);
    var nested = new SelectStatement(List.of(new ScalarSubqueryExpression<>(exact),
        new ScalarSubqueryExpression<>(new SelectStatement(
            List.of(AggregateExpression.avgAsDouble(table.approximate)), table))), new NumberTable("o"));
    for (SelectStatement query : List.of(exact, approximate, nested)) {
      var analysis = SemanticValidator.analyzeComplete(query);
      assertFalse(analysis.requiresStandardExpressionLowering());
      assertFalse(analysis.hasRealSum());
      assertTrue(analysis.hasSmallIntegerAverage() || analysis.hasDoubleAggregate());
      ResolvedStatement resolved = ResolvedStatement.analyze(query);
      assertSame(query, StandardSqlLowering.postgresql(allFeatures()).lower(resolved));
      assertFalse(query == StandardSqlLowering.h2(allFeatures()).lower(resolved));
    }
    var real = new SelectStatement(List.of(AggregateExpression.sumAsDouble(table.real)), table);
    var analysis = SemanticValidator.analyzeComplete(real);
    assertTrue(analysis.hasRealSum());
    assertFalse(analysis.requiresStandardExpressionLowering());
    assertFalse(analysis.hasSmallIntegerAverage());
    assertFalse(analysis.hasDoubleAggregate());
    ResolvedStatement resolved = ResolvedStatement.analyze(real);
    assertSame(real, StandardSqlLowering.h2(allFeatures()).lower(resolved));
    assertFalse(real == StandardSqlLowering.postgresql(allFeatures()).lower(resolved));
  }

  @Test
  void widensPostgreSqlRealSumBeforeAccumulation() {
    NumberTable table = new NumberTable("n");
    var template = compile("postgresql", new SelectStatement(
        List.of(AggregateExpression.sumAsDouble(table.real), AggregateExpression.avgAsDouble(table.real)), table));
    assertEquals("SELECT SUM(CAST(\"n\".\"real_value\" AS DOUBLE PRECISION)), AVG(\"n\".\"real_value\") FROM \"numbers\" AS \"n\"", template.sql());
    assertTrue(template.parameterLayout().isEmpty());
  }

  @Test
  void h2UsesFiniteExactInputsOnlyForSmallIntegerAverages() {
    NumberTable table = new NumberTable("n");
    var template = compile("h2", new SelectStatement(List.of(
        AggregateExpression.avgAsBigDecimal(table.tiny), AggregateExpression.avgAsBigDecimal(table.small),
        AggregateExpression.avgAsBigDecimal(table.category), AggregateExpression.avgAsBigDecimal(table.id),
        AggregateExpression.sumAsBigDecimal(table.decimal)), table));
    assertEquals("SELECT AVG(CAST(\"n\".\"tiny_value\" AS DECIMAL(3,0))), AVG(CAST(\"n\".\"small_value\" AS DECIMAL(5,0))), "
        + "AVG(CAST(\"n\".\"category\" AS DECIMAL(10,0))), AVG(\"n\".\"id\"), SUM(\"n\".\"decimal_value\") FROM \"numbers\" AS \"n\"", template.sql());
  }

  @Test
  void h2CastsDoubleResultsAfterTheNativeAggregateAndLeavesRealAlone() {
    NumberTable table = new NumberTable("n");
    assertEquals("SELECT CAST(SUM(\"n\".\"double_value\") AS DOUBLE PRECISION), CAST(AVG(\"n\".\"double_value\") AS DOUBLE PRECISION), "
            + "SUM(\"n\".\"real_value\"), AVG(\"n\".\"real_value\") FROM \"numbers\" AS \"n\"",
        compile("h2", new SelectStatement(List.of(AggregateExpression.sumAsDouble(table.approximate),
            AggregateExpression.avgAsDouble(table.approximate), AggregateExpression.sumAsDouble(table.real),
            AggregateExpression.avgAsDouble(table.real)), table)).sql());
  }

  @Test
  void ordinaryQueriesReturnTheirOriginalStructureWithoutAggregateObjects() {
    NumberTable table = new NumberTable("n");
    SelectStatement statement = new SelectStatement(List.of(table.id), table);
    ResolvedStatement resolved = ResolvedStatement.analyze(statement);
    assertSame(statement, StandardSqlLowering.postgresql(allFeatures()).lower(resolved));
    assertSame(statement, StandardSqlLowering.h2(allFeatures()).lower(resolved));
  }

  @Test
  void aggregatesWithoutConversionsAndColumnNullChecksKeepTheirOriginalStructure() {
    NumberTable table = new NumberTable("n");
    var aggregates = new SelectStatement(List.of(AggregateExpression.countAll(),
        AggregateExpression.count(table.id), AggregateExpression.min(table.id),
        AggregateExpression.max(table.id), AggregateExpression.sumAsLong(table.category),
        AggregateExpression.avgAsBigDecimal(table.id)), table);
    var nullCheck = new SelectStatement(List.of(table.id), table,
        new NullPredicate(table.category, NullOperator.IS_NULL));
    var columnGroup = grouped(table,
        List.of(table.category, AggregateExpression.countAll()), List.of(table.category));
    for (SelectStatement statement : List.of(aggregates, nullCheck, columnGroup)) {
      ResolvedStatement resolved = ResolvedStatement.analyze(statement);
      assertSame(statement, StandardSqlLowering.postgresql(allFeatures()).lower(resolved));
      assertSame(statement, StandardSqlLowering.h2(allFeatures()).lower(resolved));
    }
    CountAst count = new CountAst(table, null, null);
    ResolvedStatement resolvedCount = ResolvedStatement.analyze(count);
    assertSame(count, StandardSqlLowering.postgresql(allFeatures()).lower(resolvedCount));
    assertSame(count, StandardSqlLowering.h2(allFeatures()).lower(resolvedCount));
  }

  @Test
  void nativeColumnExpressionsDoNotRequestTheDedicatedTypingTraversal() {
    NumberTable table = new NumberTable("n");
    List<SqlExpression<?>> expressions = List.of(
        new ArithmeticExpression<>(table.decimal, ArithmeticOperator.ADD, table.decimal),
        new CastExpression<>(table.category, Long.class),
        new CoalesceExpression<>(List.of(table.decimal, table.decimal)),
        new CaseExpression<>(List.of(new CaseWhen<>(table.category.gt(table.category), table.decimal)),
            table.decimal),
        new CaseExpression<>(List.of(new CaseWhen<>(table.category.gt(table.category),
            LiteralExpression.one(Integer.class))), LiteralExpression.zero(Integer.class)),
        new ConcatExpression(List.of(table.text, table.text)), new IncrementExpression<>(table.id));
    for (SqlExpression<?> expression : expressions) {
      var selected = new SelectStatement(List.of(expression), table);
      var grouped = grouped(table, List.of(expression, AggregateExpression.countAll()),
          List.of(expression));
      for (SelectStatement query : List.of(selected, grouped,
          new SelectStatement(List.of(new ScalarSubqueryExpression<>(selected)), new NumberTable("o")))) {
        assertFalse(SemanticValidator.analyzeComplete(query).requiresStandardExpressionLowering());
        ResolvedStatement resolved = ResolvedStatement.analyze(query);
        assertSame(query, StandardSqlLowering.postgresql(allFeatures()).lower(resolved));
        assertSame(query, StandardSqlLowering.h2(allFeatures()).lower(resolved));
      }
    }
    var value = new ParameterSlot<>(0, BigDecimal.class, false);
    var mixed = new SelectStatement(List.of(value, expressions.getFirst()), table);
    assertTrue(SemanticValidator.analyzeComplete(mixed).requiresStandardExpressionLowering());
    var otherwise = LiteralExpression.nullLiteral(BigDecimal.class);
    var nullCase = new CaseExpression<>(List.of(new CaseWhen<>(new BooleanPredicate(true), otherwise)),
        otherwise);
    assertTrue(SemanticValidator.analyzeComplete(new SelectStatement(List.of(nullCase), table))
        .requiresStandardExpressionLowering());
  }

  @Test
  void customDialectIdsKeepExplicitDecimalTemporalAndBinaryFormats() {
    NumberTable table = new NumberTable("n");
    var query = new SelectStatement(List.of(new ParameterSlot<>(0, BigDecimal.class, false),
        new ParameterSlot<>(1, LocalTime.class, false), new ParameterSlot<>(2, OffsetTime.class, false),
        new ParameterSlot<>(3, LocalDateTime.class, false),
        new ParameterSlot<>(4, OffsetDateTime.class, false),
        new ParameterSlot<>(5, byte[].class, false)), table);
    for (String id : List.of("postgresql", "h2")) {
      var target = id.equals("h2") ? PortableLowering.AggregateTarget.H2
          : PortableLowering.AggregateTarget.POSTGRESQL;
      var custom = new FixtureDialect("custom-" + id, allFeatures(), aggregateLowering(id), target);
      SqlTemplate actual = SqlCompiler.compile(query, custom);
      assertEquals(compile(id, query).sql(), actual.sql());
      assertEquals(List.of(0, 1, 2, 3, 4, 5), actual.parameterLayout().slots().stream()
          .map(ParameterSlot::ordinal).toList());
      assertTrue(actual.sql().contains(id.equals("h2") ? " AS DECFLOAT)" : " AS DECIMAL)"));
      assertTrue(actual.sql().contains(id.equals("h2") ? " AS TIME(9))" : " AS TIME)"));
      assertTrue(actual.sql().contains(id.equals("h2") ? " AS VARBINARY)" : " AS BYTEA)"));
      var amount = new ParameterSlot<>(0, BigDecimal.class, false);
      var key = new CoalesceExpression<>(List.of(table.decimal, amount));
      var grouped = grouped(table, List.of(key), List.of(key));
      SqlTemplate values = SqlCompiler.compile(grouped, custom);
      assertEquals(compile(id, grouped).sql(), values.sql());
      assertTrue(values.sql().contains(id.equals("h2")
          ? "VALUES (CAST(? AS DECFLOAT)" : "VALUES (CAST(? AS DECIMAL)"));
    }
  }

  @Test
  void isolatedUuidNullParametersHaveAnExplicitSqlType() {
    NumberTable table = new NumberTable("n");
    var uuid = new ParameterSlot<>(0, UUID.class, true);
    for (String id : List.of("postgresql", "h2")) {
      for (NullOperator operator : NullOperator.values()) {
        var query = new SelectStatement(List.of(table.id), table, new NullPredicate(uuid, operator));
        SqlTemplate template = compile(id, query);
        assertTrue(template.sql().contains("CAST(? AS UUID) "
            + (operator == NullOperator.IS_NULL ? "IS NULL" : "IS NOT NULL")));
        assertEquals(List.of(uuid), template.parameterLayout().slots());
      }
    }
  }

  @Test
  void emptyMembershipRetainsTheEnclosingAggregationWithoutDiscardedParameters() {
    NumberTable table = new NumberTable("n");
    NumberTable inner = new NumberTable("i");
    var nested = new ScalarSubqueryExpression<>(
        new SelectStatement(List.of(AggregateExpression.countAll()), inner));
    var parameter = new ParameterSlot<>(0, Integer.class, false);
    for (String id : List.of("postgresql", "h2")) {
      for (boolean negated : List.of(false, true)) {
        var empty = new InPredicate<>(AggregateExpression.sumAsLong(parameter), List.of(), negated);
        var query = new SelectStatement(List.of(empty, nested), table);
        SqlTemplate template = compile(id, query);
        assertTrue(template.sql().contains("SUM(CAST(NULL AS INTEGER)) "
            + (negated ? "IS NULL" : "IS NOT NULL")));
        assertTrue(template.parameterLayout().isEmpty());
      }
    }
  }

  @Test
  void discardingOnlyASubqueryAggregateDoesNotTurnTheOuterBlockIntoAGroupedQuery() {
    NumberTable table = new NumberTable("n");
    NumberTable inner = new NumberTable("i");
    var scalar = new ScalarSubqueryExpression<>(
        new SelectStatement(List.of(AggregateExpression.countAll()), inner));
    var query = new SelectStatement(List.of(new InPredicate<>(scalar, List.of(), false)), table);
    for (String id : List.of("postgresql", "h2")) {
      assertEquals("SELECT FALSE FROM \"numbers\" AS \"n\"", compile(id, query).sql());
    }
  }

  @Test
  void countPredicatesAndDistinctExpressionsProtectTheirNestedOutputs() {
    NumberTable table = new NumberTable("n");
    NumberTable inner = new NumberTable("i");
    ScalarSubqueryExpression<Long> minimum = new ScalarSubqueryExpression<>(
        new SelectStatement(List.of(AggregateExpression.min(inner.id)), inner));
    ScalarSubqueryExpression<Long> maximum = new ScalarSubqueryExpression<>(
        new SelectStatement(List.of(AggregateExpression.max(inner.id)), inner));
    var predicate = new CountAst(table, table.id.gt(minimum), null);
    assertRejected(predicate, input -> new CountAst(table, table.id.gt(maximum), null));
    var distinct = new CountAst(table, null, minimum);
    assertRejected(distinct, input -> new CountAst(table, null, maximum));
    assertRejected(distinct, input -> new CountAst(table, null, minimum, true));
  }

  @Test
  void nestedGroupingCannotBeRemovedWhileAnotherBlockStillAggregates() {
    NumberTable table = new NumberTable("n");
    NumberTable inner = new NumberTable("i");
    var count = AggregateExpression.countAll();
    var child = grouped(inner, List.of(count), List.of(inner.category));
    var original = new SelectStatement(List.of(count, new ScalarSubqueryExpression<>(child)), table);
    assertRejected(original, input -> new SelectStatement(
        List.of(count, new ScalarSubqueryExpression<>(new SelectStatement(List.of(count), inner))), table));
  }

  @Test
  void countCorrespondenceStillAcceptsRequiredNestedNumericConversions() {
    NumberTable table = new NumberTable("n");
    NumberTable inner = new NumberTable("i");
    var sum = new ScalarSubqueryExpression<>(
        new SelectStatement(List.of(AggregateExpression.sumAsDouble(inner.real)), inner));
    var count = new CountAst(table, null, sum);
    var dialect = new FixtureDialect("postgresql", allFeatures(), StandardSqlLowering.postgresql(allFeatures()));
    var template = SqlCompiler.compile(count, dialect);
    assertTrue(template.sql().contains("SUM(CAST(\"i\".\"real_value\" AS DOUBLE PRECISION))"));
    assertTrue(template.parameterLayout().isEmpty());
  }

  @Test
  void parameterOnlyArithmeticKeepsTheDeclaredIntegerOperationDomain() {
    NumberTable table = new NumberTable("n");
    var first = new ParameterSlot<>(0, Integer.class, false);
    var second = new ParameterSlot<>(1, Integer.class, false);
    var divide = new ArithmeticExpression<>(first, ArithmeticOperator.DIVIDE, second);
    for (String id : List.of("postgresql", "h2")) {
      assertEquals("SELECT (CAST(? AS INTEGER) / CAST(? AS INTEGER)) FROM \"numbers\" AS \"n\"",
          compile(id, new SelectStatement(List.of(divide), table)).sql());
    }
  }

  @Test
  void typedBooleanNullDoesNotFallBackToAnUnknownSelectType() {
    NumberTable table = new NumberTable("n");
    var query = new SelectStatement(List.of(LiteralExpression.nullLiteral(Boolean.class)), table);
    for (String id : List.of("postgresql", "h2")) {
      assertEquals("SELECT CAST(NULL AS BOOLEAN) FROM \"numbers\" AS \"n\"", compile(id, query).sql());
    }
  }

  @Test
  void castsRequiredForGroupingDoNotCreateADuplicatePhysicalKey() {
    NumberTable table = new NumberTable("n");
    var one = LiteralExpression.one(Integer.class);
    var explicit = new CastExpression<>(one, Integer.class);
    var query = grouped(table, List.of(one, AggregateExpression.countAll()), List.of(one, explicit));
    assertEquals(2, query.groupBy().size());
    for (String id : List.of("postgresql", "h2")) {
      assertEquals("SELECT CAST(1 AS INTEGER), COUNT(*) FROM \"numbers\" AS \"n\" GROUP BY CAST(1 AS INTEGER)", compile(id, query).sql());
    }
  }

  @Test
  void parameterizedKeysUseOneTrailingValuesRowAndKeepPhysicalSlotOrder() {
    NumberTable table = new NumberTable("n");
    ParameterSlot<Integer> step = new ParameterSlot<>(0, Integer.class, false);
    ParameterSlot<Integer> cutoff = new ParameterSlot<>(1, Integer.class, false);
    var selected = new ArithmeticExpression<>(table.category, ArithmeticOperator.ADD, step);
    var declaredKey = new ArithmeticExpression<>(table.category, ArithmeticOperator.ADD,
        new ParameterSlot<>(0, Integer.class, false));
    SelectStatement statement = new SelectStatement(false, List.of(selected, AggregateExpression.countAll()),
        List.of(), table, table.category.gt(step), List.of(declaredKey),
        new ComparisonPredicate<>(selected, ComparisonOperator.GREATER_THAN, cutoff),
        List.of(new OrderByItem(selected, OrderDirection.ASC, NullOrder.DIALECT_DEFAULT)), null);

    for (String id : List.of("postgresql", "h2")) {
      SqlTemplate template = compile(id, statement);
      assertEquals("SELECT (\"n\".\"category\" + \"_skis_gp0\".\"p0\"), COUNT(*) FROM \"numbers\" AS \"n\" "
          + "CROSS JOIN (VALUES (CAST(? AS INTEGER))) AS \"_skis_gp0\"(\"p0\") WHERE \"n\".\"category\" > ? "
          + "GROUP BY (\"n\".\"category\" + \"_skis_gp0\".\"p0\") HAVING (\"n\".\"category\" + \"_skis_gp0\".\"p0\") > ? "
          + "ORDER BY (\"n\".\"category\" + \"_skis_gp0\".\"p0\") ASC", template.sql());
      assertEquals(List.of(step, step, cutoff), template.parameterLayout().slots());
    }
  }

  @Test
  void booleanParameterizedGroupsRequestLoweringForEveryPredicateKind() {
    NumberTable table = new NumberTable("n");
    var first = new ParameterSlot<>(0, Integer.class, false);
    var second = new ParameterSlot<>(1, Integer.class, false);
    var pattern = new ParameterSlot<>(0, String.class, false);
    List<SqlExpression<?>> keys = List.of(table.category.gt(first),
        new BetweenPredicate<>(table.category, first, second),
        new InPredicate<>(table.category, List.of(first), false),
        new LikePredicate(table.text, pattern));
    for (SqlExpression<?> key : keys) {
      var query = grouped(table, List.of(key, AggregateExpression.countAll()), List.of(key));
      assertTrue(SemanticValidator.analyzeComplete(query).requiresStandardExpressionLowering());
      List<ParameterSlot<?>> expected = key instanceof BetweenPredicate<?> ? List.of(first, second)
          : key instanceof LikePredicate ? List.of(pattern) : List.of(first);
      for (String id : List.of("postgresql", "h2")) {
        SqlTemplate template = compile(id, query);
        assertTrue(template.sql().contains("CROSS JOIN (VALUES (CAST(? AS"), template.sql());
        assertEquals(expected, template.parameterLayout().slots());
        var counted = grouped(table, List.of(AggregateExpression.countAll()), List.of(key));
        var nested = new SelectStatement(List.of(new ScalarSubqueryExpression<>(counted)),
            new NumberTable("o"));
        assertEquals(expected, compile(id, nested).parameterLayout().slots());
      }
    }
    var ordinary = new SelectStatement(List.of(table.id), table, table.category.gt(first));
    assertFalse(SemanticValidator.analyzeComplete(ordinary).requiresStandardExpressionLowering());
    ResolvedStatement resolved = ResolvedStatement.analyze(ordinary);
    assertSame(ordinary, StandardSqlLowering.postgresql(allFeatures()).lower(resolved));
    assertSame(ordinary, StandardSqlLowering.h2(allFeatures()).lower(resolved));
  }

  @Test
  void sparseConversionsKeepUnchangedOperandsAndRebuildEveryChangedPosition() {
    NumberTable table = new NumberTable("n");
    var unchanged = new ArithmeticExpression<>(table.decimal, ArithmeticOperator.ADD, table.decimal);
    var nativeCoalesce = new CoalesceExpression<>(List.of(table.decimal, unchanged));
    for (int changedIndex = 0; changedIndex < 3; changedIndex++) {
      List<SqlExpression<BigDecimal>> operands =
          new java.util.ArrayList<>(List.of(unchanged, table.decimal, unchanged));
      operands.set(changedIndex, new ParameterSlot<>(0, BigDecimal.class, false));
      var coalesce = new CoalesceExpression<>(operands);
      var query = new SelectStatement(List.of(unchanged, coalesce, nativeCoalesce), table);
      var lowered = (SelectStatement) StandardSqlLowering.h2(allFeatures())
          .lower(ResolvedStatement.analyze(query));
      assertSame(unchanged, lowered.selections().getFirst());
      assertSame(nativeCoalesce, lowered.selections().get(2));
      var rewritten = (CoalesceExpression<?>) lowered.selections().get(1);
      assertEquals(3, rewritten.operands().size());
      for (int index = 0; index < operands.size(); index++) {
        if (index == changedIndex) {
          var cast = (CastExpression<?>) rewritten.operands().get(index);
          assertSame(operands.get(index), cast.operand());
        } else {
          assertSame(operands.get(index), rewritten.operands().get(index));
        }
      }
    }
  }

  @Test
  void independentParametersRemainSeparateAndGeneratedAliasesAvoidUserQualifiers() {
    NumberTable table = new NumberTable("_skis_gp0");
    ParameterSlot<Integer> first = new ParameterSlot<>(0, Integer.class, false);
    ParameterSlot<Integer> second = new ParameterSlot<>(1, Integer.class, false);
    var firstKey = new ArithmeticExpression<>(table.category, ArithmeticOperator.ADD, first);
    var secondKey = new ArithmeticExpression<>(table.category, ArithmeticOperator.ADD, second);
    var template = compile("postgresql", grouped(table, List.of(firstKey, secondKey), List.of(firstKey, secondKey)));
    assertTrue(template.sql().contains("AS \"_skis_gp1\"(\"p0\", \"p1\")"));
    assertEquals(List.of(first, second), template.parameterLayout().slots());
  }

  @Test
  void columnGroupsDoNotAcquireAnExtraRelation() {
    NumberTable table = new NumberTable("n");
    String sql = compile("h2", grouped(table, List.of(table.category, AggregateExpression.countAll()), List.of(table.category))).sql();
    assertEquals("SELECT \"n\".\"category\", COUNT(*) FROM \"numbers\" AS \"n\" GROUP BY \"n\".\"category\"", sql);
  }

  @Test
  void valuesFollowTheWholeOuterJoinAndNeverBecomeVisibleToTheOriginalOn() {
    NumberTable left = new NumberTable("l");
    NumberTable right = new NumberTable("r");
    ParameterSlot<Integer> step = new ParameterSlot<>(0, Integer.class, false);
    var key = new ArithmeticExpression<>(left.category, ArithmeticOperator.ADD, step);
    var onValue = new ArithmeticExpression<>(right.category, ArithmeticOperator.ADD, step);
    FromClause from = new FromClause(left,
        List.of(new JoinClause(JoinType.LEFT, right, left.category.eq(onValue))));
    SelectStatement statement = new SelectStatement(false, List.of(key, AggregateExpression.count(right.id)),
        List.of(), from, null, List.of(key), null, List.of(), null);
    SqlTemplate template = compile("postgresql", statement);
    assertTrue(template.sql().contains("LEFT JOIN \"numbers\" AS \"r\" ON \"l\".\"category\" = (\"r\".\"category\" + ?) CROSS JOIN (VALUES"));
    assertEquals(List.of(step, step), template.parameterLayout().slots());
  }

  @Test
  void nestedAggregateBlocksRetainTheirCorrelationAndParameterLayouts() {
    NumberTable outer = new NumberTable("o");
    NumberTable inner = new NumberTable("i");
    ParameterSlot<Integer> step = new ParameterSlot<>(0, Integer.class, false);
    ParameterSlot<Long> minimum = new ParameterSlot<>(1, Long.class, false);
    var key = new ArithmeticExpression<>(outer.category, ArithmeticOperator.ADD, step);
    var scalar = new ScalarSubqueryExpression<>(new SelectStatement(List.of(AggregateExpression.countAll()), inner,
        LogicalPredicate.and(List.of(inner.category.eq(outer.category), inner.id.gt(minimum)))));
    SqlTemplate template = compile("postgresql", grouped(outer, List.of(key, scalar), List.of(key, outer.category)));
    assertTrue(template.sql().contains("SELECT COUNT(*) FROM \"numbers\" AS \"i\" WHERE"));
    assertEquals(List.of(minimum, step), template.parameterLayout().slots());
  }

  @Test
  void allKnownGroupSqlTypesHaveTypedConstantKeysAndOtherFailsBeforeLowering() {
    List<Class<?>> types = List.of(Boolean.class, Byte.class, Short.class, Integer.class, Long.class,
        Float.class, Double.class, BigInteger.class, BigDecimal.class, Character.class, String.class,
        byte[].class, UUID.class, LocalDate.class, LocalTime.class, OffsetTime.class,
        LocalDateTime.class, OffsetDateTime.class, Instant.class);
    NumberTable table = new NumberTable("n");
    for (String id : List.of("postgresql", "h2")) {
      for (Class<?> type : types) {
        ParameterSlot<?> slot = new ParameterSlot<>(0, type, true);
        SqlTemplate template = compile(id, grouped(table, List.of(AggregateExpression.countAll()), List.of(slot)));
        assertEquals(List.of(slot), template.parameterLayout().slots());
        assertTrue(template.sql().contains(" GROUP BY CAST(? AS "), type.getName());
        assertFalse(template.sql().contains("CROSS JOIN"));
      }
      SqlRenderException failure = assertThrows(SqlRenderException.class,
          () -> compile(id, grouped(table, List.of(AggregateExpression.countAll()), List.of(table.other))));
      assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, failure.code());
    }
  }

  @Test
  void controlledIntegersAndNullRemainValueExpressionsInExplicitGrouping() {
    NumberTable table = new NumberTable("n");
    var one = LiteralExpression.one(Integer.class);
    assertTrue(compile("postgresql", grouped(table, List.of(one, AggregateExpression.countAll()), List.of(one)))
        .sql().endsWith("GROUP BY CAST(1 AS INTEGER)"));
    var nullValue = LiteralExpression.nullLiteral(String.class);
    assertTrue(compile("h2", grouped(table, List.of(nullValue, AggregateExpression.countAll()), List.of(nullValue)))
        .sql().endsWith("GROUP BY CAST(NULL AS VARCHAR)"));
  }

  @Test
  void capabilitiesAreIndependentAndRecursive() {
    NumberTable table = new NumberTable("n");
    List<SqlExpression<?>> aggregates = List.of(AggregateExpression.countAll(), AggregateExpression.sumAsLong(table.category),
        AggregateExpression.avgAsBigDecimal(table.category), AggregateExpression.min(table.category), AggregateExpression.max(table.category));
    List<DialectFeature> features = List.of(DialectFeature.COUNT_AGGREGATE, DialectFeature.SUM_AGGREGATE,
        DialectFeature.AVG_AGGREGATE, DialectFeature.MIN_AGGREGATE, DialectFeature.MAX_AGGREGATE);
    for (int index = 0; index < aggregates.size(); index++) {
      DialectFeature missing = features.get(index);
      var scalar = new ScalarSubqueryExpression<>(new SelectStatement(List.of(aggregates.get(index)), table));
      EnumSet<DialectFeature> enabled = EnumSet.allOf(DialectFeature.class);
      enabled.remove(missing);
      FixtureDialect dialect = new FixtureDialect("postgresql", DialectCapabilities.of(enabled.toArray(DialectFeature[]::new)), DialectLowering.identity());
      SqlRenderException failure = assertThrows(SqlRenderException.class,
          () -> SqlCompiler.compile(new SelectStatement(List.of(scalar), new NumberTable("o")), dialect));
      assertEquals(missing.name(), failure.diagnostics().dialect().missingCapability());
      assertTrue(failure.getMessage().contains("query block"));
    }
  }

  @Test
  void sameTypeColumnSubstitutionAndOutputReorderingRemainRejected() {
    NumberTable table = new NumberTable("n");
    SelectStatement statement = new SelectStatement(List.of(table.id, table.otherId), table);
    assertRejected(statement, input -> replaceSelections(statement, List.of(table.otherId, table.id)));
    assertRejected(statement, input -> replaceSelections(statement, List.of(table.otherId, table.otherId)));
    var sum = new SelectStatement(List.of(AggregateExpression.sumAsDouble(table.real)), table);
    assertRejected(sum, input -> replaceSelections(sum, List.of(AggregateExpression.avgAsDouble(table.real))));
    var first = new ParameterSlot<>(0, Long.class, false);
    var second = new ParameterSlot<>(1, Long.class, false);
    var parameters = new SelectStatement(List.of(first, second), table);
    assertRejected(parameters, input -> replaceSelections(parameters, List.of(second, first)));
    var average = new SelectStatement(List.of(AggregateExpression.avgAsBigDecimal(table.category)), table);
    var narrowed = new CastExpression<>(table.category, BigDecimal.class, SqlType.DECIMAL, new DecimalPrecision(1, 0));
    assertRejected(average, input -> replaceSelections(average, List.of(AggregateExpression.avgAsBigDecimal(narrowed))));
  }

  @Test
  void parameterColumnsCannotSwapTwoOtherwiseLegalGroupedOutputs() {
    NumberTable table = new NumberTable("n");
    var first = new ParameterSlot<>(0, Integer.class, false);
    var second = new ParameterSlot<>(1, Integer.class, false);
    var firstKey = new ArithmeticExpression<>(table.category, ArithmeticOperator.ADD, first);
    var secondKey = new ArithmeticExpression<>(table.category, ArithmeticOperator.ADD, second);
    var statement = grouped(table, List.of(firstKey, secondKey), List.of(firstKey, secondKey));
    assertRejected(statement, input -> {
      SelectStatement lowered = (SelectStatement) StandardSqlLowering.postgresql(allFeatures()).lower(input);
      ParameterRelationSource source = (ParameterRelationSource) lowered.joins().getLast().right();
      var changed = new ArithmeticExpression<>(table.category, ArithmeticOperator.ADD,
          new DerivedColumnExpression<Integer>(source.reference(), 1));
      return replaceSelections(lowered, List.of(changed, lowered.selections().get(1)));
    });
  }

  @Test
  void hiddenAndNestedOutputsCannotBeSilentlyChanged() {
    NumberTable table = new NumberTable("n");
    NumberTable outer = new NumberTable("o");
    var scalar = new ScalarSubqueryExpression<>(new SelectStatement(List.of(table.id), table));
    SelectStatement statement = new SelectStatement(List.of(scalar), outer);
    assertRejected(statement, input -> replaceSelections(statement,
        List.of(new ScalarSubqueryExpression<>(new SelectStatement(List.of(table.otherId), table)))));
    var hidden = new SelectStatement(false, List.of(table.id), List.of(new HiddenSelection(table.otherId, Identifier.of("h"))),
        table, null, List.of(), null);
    assertRejected(hidden, input -> new SelectStatement(false, hidden.selections(),
        List.of(new HiddenSelection(table.otherId, Identifier.of("changed"))), table, null, List.of(), null));
  }

  private static void assertRejected(StatementAst statement, DialectLowering lowering) {
    SqlRenderException failure = assertThrows(SqlRenderException.class,
        () -> SqlCompiler.compile(statement, new FixtureDialect("postgresql", allFeatures(), lowering)));
    assertEquals(SkisErrorCode.DIALECT_INVALID_STRUCTURE, failure.code());
  }

  private static SelectStatement replaceSelections(SelectStatement statement, List<SqlExpression<?>> selections) {
    return new SelectStatement(statement.distinct(), selections, statement.hiddenSelections(), statement.fromClause(),
        statement.where().orElse(null), statement.groupBy(), statement.having().orElse(null), statement.orderBy(), statement.pagination().orElse(null));
  }

  private static SelectStatement grouped(NumberTable table, List<SqlExpression<?>> selections, List<SqlExpression<?>> keys) {
    return new SelectStatement(false, selections, List.of(), table, null, keys, null, List.of(), null);
  }

  private static SqlTemplate compile(String id, StatementAst statement) {
    return SqlCompiler.compile(statement, new FixtureDialect(id, allFeatures(), aggregateLowering(id)));
  }

  private static DialectLowering aggregateLowering(String id) {
    return id.equals("h2") ? StandardSqlLowering.h2(allFeatures())
        : StandardSqlLowering.postgresql(allFeatures());
  }

  private static DialectCapabilities allFeatures() {
    return DialectCapabilities.of(DialectFeature.values());
  }

  private record FixtureDialect(String id, DialectCapabilities capabilities, DialectLowering lowering,
      PortableLowering.AggregateTarget typeFormat) implements Dialect {
    private FixtureDialect(String id, DialectCapabilities capabilities, DialectLowering lowering) {
      this(id, capabilities, lowering, id.equals("h2") ? PortableLowering.AggregateTarget.H2
          : PortableLowering.AggregateTarget.POSTGRESQL);
    }
    @Override public IdentifierRules identifierRules() { return StandardIdentifierRules.INSTANCE; }
    @Override public SqlRenderer renderer() {
      return switch (typeFormat) {
        case H2 -> StandardSqlRenderer.h2(this);
        case POSTGRESQL -> StandardSqlRenderer.postgresql(this);
      };
    }
  }

  private record Row() {}

  private static final class NumberTable extends TableExpression<Row> {
    private static final PropertyMeta<Row, Long> ID = property(0, "id", Long.class);
    private static final PropertyMeta<Row, Long> OTHER_ID = property(1, "other_id", Long.class);
    private static final PropertyMeta<Row, Integer> CATEGORY = property(2, "category", Integer.class);
    private static final PropertyMeta<Row, Byte> TINY = property(3, "tiny_value", Byte.class);
    private static final PropertyMeta<Row, Short> SMALL = property(4, "small_value", Short.class);
    private static final PropertyMeta<Row, Float> REAL = property(5, "real_value", Float.class);
    private static final PropertyMeta<Row, Double> DOUBLE = property(6, "double_value", Double.class);
    private static final PropertyMeta<Row, BigDecimal> DECIMAL = property(7, "decimal_value", BigDecimal.class);
    private static final PropertyMeta<Row, Object> OTHER = property(8, "other_value", Object.class);
    private static final PropertyMeta<Row, String> TEXT = property(9, "text_value", String.class);
    private static final EntityMeta<Row> ENTITY = EntityMeta.simple(Row.class, TableMeta.of("numbers"),
        List.of(ID, OTHER_ID, CATEGORY, TINY, SMALL, REAL, DOUBLE, DECIMAL, OTHER, TEXT), new PrimaryKeyMeta<>(List.of(ID)), false);
    private final ColumnExpression<Row, Long> id = column(ID);
    private final ColumnExpression<Row, Long> otherId = column(OTHER_ID);
    private final ColumnExpression<Row, Integer> category = column(CATEGORY);
    private final ColumnExpression<Row, Byte> tiny = column(TINY);
    private final ColumnExpression<Row, Short> small = column(SMALL);
    private final ColumnExpression<Row, Float> real = column(REAL);
    private final ColumnExpression<Row, Double> approximate = column(DOUBLE);
    private final ColumnExpression<Row, BigDecimal> decimal = column(DECIMAL);
    private final ColumnExpression<Row, Object> other = column(OTHER);
    private final ColumnExpression<Row, String> text = column(TEXT);
    private NumberTable(String alias) { super(ENTITY, Identifier.of(alias)); }
    @Override public NumberTable as(Identifier alias) { return new NumberTable(alias.value()); }
    private static <V> PropertyMeta<Row, V> property(int ordinal, String name, Class<V> type) {
      return new PropertyMeta<>(ordinal, name, type, ColumnMeta.of(name, ordinal > 1));
    }
  }
}
