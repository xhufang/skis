package io.skis.query.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.IdentifierRules;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.dialect.spi.SqlRenderer;
import io.skis.dialect.support.StandardIdentifierRules;
import io.skis.dialect.support.StandardSqlRenderer;
import io.skis.mapping.internal.EntityRuntimeRegistry;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.JdbcReadContext;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.mapping.spi.RowReadContext;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.NonNullSelectable;
import io.skis.query.QueryOperations;
import io.skis.query.QueryTable;
import io.skis.query.SelectQuery;
import io.skis.query.Selectable;
import io.skis.query.SliceRequest;
import io.skis.query.Sql;
import io.skis.query.spi.ProjectionMapping;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.HiddenSelection;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlType;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Actual mapping identity and result-reading contracts; compilation must never acquire JDBC. */
class AggregateInputMappingTest {

  private static final PropertyMeta<Sample, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Sample, Integer> NUMBER =
      new PropertyMeta<>(1, "number", Integer.class, ColumnMeta.of("number", true));
  private static final PropertyMeta<Sample, String> LABEL =
      new PropertyMeta<>(2, "label", String.class, ColumnMeta.of("label", false));
  private static final EntityMeta<Sample> ENTITY = EntityMeta.simple(
      Sample.class, TableMeta.of("sample"), List.of(ID, NUMBER, LABEL),
      new PrimaryKeyMeta<>(List.of(ID)), false);
  private static final SampleTable TABLE = new SampleTable("s");

  @Test
  void scalarAndProjectionReadersDoNotCompileDiagnosticPreviews() throws Exception {
    var operations = operations(false);
    for (boolean projection : List.of(false, true)) {
      AtomicInteger builds = new AtomicInteger();
      NonNullSelectable<Long> count = StandardExpressions.nonNull(
          StandardExpressions.unary(TABLE.id, operand -> {
            builds.incrementAndGet();
            return AggregateExpression.count(operand);
          }));
      // Expression construction validates a descriptor; count only actual query compilation.
      builds.set(0);
      var query = projection
          ? operations.select(strictLongMapping().bind(count)).from(TABLE)
          : operations.select(count).from(TABLE);
      var compiled = compile(query);
      assertEquals(1, builds.get());
      assertEquals(2L,
          compiled.plan().rowDecoder().decode(resultSet(Map.of(1, 2L)), RowReadContext.EMPTY));
      QueryValidationException failure = assertThrows(QueryValidationException.class,
          () -> compiled.plan().rowDecoder().decode(resultSet(Map.of()), RowReadContext.EMPTY));
      assertEquals(SkisErrorCode.QUERY_NON_NULL_RESULT, failure.code());
      assertTrue(failure.diagnostics().safeText().contains("AggregateExpression"));
      assertEquals(1, builds.get(), "decoding and failure diagnostics must not compile a preview");
    }
  }

  @Test
  void allAggregateResultDomainsUseTheirExactBuiltInCodec() {
    var registry = registry(false);
    assertSame(JdbcCodecs.LONG, ResolvedValueMapping.codecFor(Sql.countAll(), registry));
    assertSame(JdbcCodecs.LONG, ResolvedValueMapping.codecFor(Sql.count(TABLE.number), registry));
    assertSame(JdbcCodecs.LONG, ResolvedValueMapping.codecFor(Sql.countDistinct(TABLE.label), registry));
    assertSame(JdbcCodecs.LONG, ResolvedValueMapping.codecFor(Sql.sumAsLong(TABLE.number), registry));
    assertSame(JdbcCodecs.BIG_INTEGER,
        ResolvedValueMapping.codecFor(Sql.sumAsBigInteger(TABLE.id), registry));
    assertSame(JdbcCodecs.BIG_DECIMAL,
        ResolvedValueMapping.codecFor(Sql.avgAsBigDecimal(TABLE.number), registry));
    assertSame(JdbcCodecs.BIG_DECIMAL,
        ResolvedValueMapping.codecFor(Sql.sumAsBigDecimal(Sql.value(BigDecimal.ONE)), registry));
    assertSame(JdbcCodecs.DOUBLE,
        ResolvedValueMapping.codecFor(Sql.sumAsDouble(Sql.value(1F)), registry));
    assertSame(JdbcCodecs.DOUBLE,
        ResolvedValueMapping.codecFor(Sql.avgAsDouble(Sql.value(1D)), registry));
    assertSame(JdbcCodecs.INTEGER, ResolvedValueMapping.codecFor(Sql.min(TABLE.number), registry));
    assertSame(JdbcCodecs.STRING, ResolvedValueMapping.codecFor(Sql.max(TABLE.label), registry));
  }

  @Test
  void exactValueTypesAndParametersResolveWithoutAPropertyOrResultRegistry() {
    var registry = registry(false);
    for (Object value : List.of(true, (byte) 1, (short) 2, 3, 4L, 1F, 2D, 'x', "x",
        BigInteger.ONE, BigDecimal.ONE, new byte[] {1}, java.util.UUID.randomUUID(),
        java.time.LocalDate.of(2026, 10, 25), java.sql.Date.valueOf("2026-10-25"),
        java.time.LocalTime.NOON, java.sql.Time.valueOf("12:00:00"),
        java.time.OffsetTime.parse("12:00:00+08:00"), java.time.LocalDateTime.of(2026, 10, 25, 12, 0),
        java.sql.Timestamp.valueOf("2026-10-25 12:00:00"), java.time.Instant.EPOCH,
        java.time.OffsetDateTime.parse("2026-10-25T12:00:00+08:00"))) {
      var selectable = Sql.value(value);
      assertSame(ResolvedValueMapping.requireBuiltinCodec(selectable.javaType(), selectable.sqlType()),
          ResolvedValueMapping.codecFor(selectable, registry));
    }
    assertSame(JdbcCodecs.INTEGER,
        ResolvedValueMapping.codecFor(Sql.value(Sql.parameter(Integer.class)), registry));
    assertSame(JdbcCodecs.INTEGER,
        ResolvedValueMapping.codecFor(Sql.value(int.class, null), registry));
  }

  @Test
  void rejectsCustomAndDelegatingCodecsForEveryRestrictedAggregate() {
    var operations = operations(true);
    for (Selectable<?> aggregate : List.of(Sql.countDistinct(TABLE.number),
        Sql.sumAsLong(TABLE.number), Sql.avgAsBigDecimal(TABLE.number),
        Sql.min(TABLE.number), Sql.max(TABLE.number), Sql.min(TABLE.label),
        Sql.sumAsBigInteger(Sql.cast(TABLE.number, Long.class, SqlType.BIGINT)))) {
      assertTypeFailure(() -> compile(operations.select(aggregate).from(TABLE)));
    }
  }

  @Test
  void restrictedAggregatesAcceptBuiltInInputsAcrossStandardExpressions() throws Exception {
    var operations = operations(false);
    for (Selectable<?> input : List.of(
        Sql.add(TABLE.number, Sql.value(1)),
        Sql.caseWhen(TABLE.number.eq(7), Sql.value(1), TABLE.number),
        Sql.coalesce(Sql.value(Integer.class, null), TABLE.number),
        Sql.cast(TABLE.number, Long.class, SqlType.BIGINT),
        Sql.concat(Sql.value("prefix"), TABLE.label))) {
      var compiled = compile(operations.select(Sql.countDistinct(input)).from(TABLE));
      assertEquals(2L,
          compiled.plan().rowDecoder().decode(resultSet(Map.of(1, 2L)), RowReadContext.EMPTY));
    }
  }

  @Test
  void standardExpressionsCheckCustomInputsAfterBuiltInOperands() {
    var operations = operations(true);
    for (Selectable<?> input : List.of(
        Sql.add(Sql.value(1), TABLE.number),
        Sql.coalesce(Sql.value(0), TABLE.number),
        Sql.caseWhen(TABLE.id.eq(TABLE.id), Sql.value(1), TABLE.number),
        Sql.concat(Sql.value("prefix"), TABLE.label))) {
      assertTypeFailure(() -> compile(operations.select(Sql.countDistinct(input)).from(TABLE)));
    }
  }

  @Test
  void restrictedAggregatesAcceptBuiltInDerivedAndScalarInputs() throws Exception {
    var operations = operations(false);
    var inner = TABLE.as("builtin_inner");
    var output = Sql.output(inner.number, "number");
    var derived = Sql.derived(Sql.select(inner.number).from(inner), "builtin_rows", output);
    var derivedPlan = compile(operations.select(Sql.countDistinct(derived.column(output))).from(derived));
    var scalar = Sql.scalar(Sql.select(inner.number).from(inner));
    var scalarPlan = compile(operations.select(Sql.countDistinct(scalar)).from(TABLE));
    for (var compiled : List.of(derivedPlan, scalarPlan)) {
      assertEquals(0, compiled.plan().parameterCount());
      assertEquals(2L,
          compiled.plan().rowDecoder().decode(resultSet(Map.of(1, 2L)), RowReadContext.EMPTY));
    }
  }

  @Test
  void aggregateInputsAreValidatedInsideRangeNegationLikeAndSubqueryPredicates() {
    var builtin = operations(false);
    var custom = operations(true);
    var inner = TABLE.as("predicate_inner");
    var sum = Sql.sumAsLong(TABLE.number);
    for (var condition : List.of(
        sum.between(1L, 10L),
        sum.isNull().not(),
        Sql.min(TABLE.label).like("prefix%"),
        sum.in(Sql.select(inner.id).from(inner)))) {
      compile(builtin.select(Sql.countAll()).from(TABLE).having(condition));
      assertTypeFailure(() -> compile(custom.select(Sql.countAll()).from(TABLE).having(condition)));
    }
  }

  @Test
  void validatesHavingOrderingCountAndPrunedOriginalOccurrencesWithoutReadingTheirResults() {
    var operations = operations(true);
    var sum = Sql.sumAsLong(TABLE.number);
    assertTypeFailure(() -> compile(operations.select(Sql.countAll()).from(TABLE).having(sum.gt(0L))));
    assertTypeFailure(() -> compile(operations.select(Sql.countAll()).from(TABLE).orderBy(sum.asc())));
    assertTypeFailure(() -> compile(operations.select(Sql.countAll()).from(TABLE).having(sum.in(List.of()))));
    assertTypeFailure(() -> compileCount(operations.select(TABLE.id).from(TABLE)
        .groupBy(TABLE.id).orderBy(sum.asc())));
    var hidden = new SelectStatement(false, List.of(AggregateExpression.countAll()),
        List.of(new HiddenSelection(SelectableSupport.expression(sum), Identifier.of("hidden_sum"))),
        QueryTableModel.of(TABLE).tableExpression(), null, List.of(), null, List.of(), null);
    assertTypeFailure(() -> QueryInputMappings.validate(ResolvedStatement.analyze(hidden), registry(true)));
  }

  @Test
  void tracesRestrictedInputsThroughDerivedAndScalarOutputsAndNestedQueries() {
    var operations = operations(true);
    var inner = TABLE.as("inner_s");
    var output = Sql.output(inner.number, "number");
    var derived = Sql.derived(Sql.select(inner.number).from(inner), "numbers", output);
    assertTypeFailure(() -> compile(operations.select(Sql.countDistinct(derived.column(output))).from(derived)));
    var scalar = Sql.scalar(Sql.select(inner.number).from(inner));
    assertTypeFailure(() -> compile(operations.select(Sql.countDistinct(scalar)).from(TABLE)));
    assertTypeFailure(() -> compile(operations.select(TABLE.id).from(TABLE)
        .where(Sql.exists(Sql.select(Sql.sumAsLong(inner.number)).from(inner)))));
    var aggregate = Sql.sumAsLong(inner.number);
    var total = Sql.output(aggregate, "total");
    var grouped = Sql.derived(Sql.select(aggregate).from(inner), "totals", total);
    assertTypeFailure(() -> compile(operations.select(Sql.countAll()).from(grouped)));
  }

  @Test
  void standardExpressionsCannotHideCustomInputsInResultsOrBindingSources() {
    var operations = operations(true);
    List<Selectable<?>> results = List.of(
        Sql.add(TABLE.number, TABLE.number),
        Sql.coalesce(TABLE.number, TABLE.number),
        Sql.caseWhen(TABLE.id.eq(TABLE.id), TABLE.number, TABLE.number),
        Sql.cast(TABLE.number, Long.class, SqlType.BIGINT),
        Sql.concat(TABLE.label, TABLE.label));
    for (Selectable<?> result : results) {
      assertTypeFailure(() -> compile(operations.select(result).from(TABLE)));
      var inner = TABLE.as("wrapped_s");
      var scalar = Sql.scalar(Sql.select(result).from(TABLE));
      assertTypeFailure(() -> compile(operations.select(scalar).from(inner)));
      var output = Sql.output(result, "wrapped");
      var derived = Sql.derived(Sql.select(result).from(TABLE), "wrapped_rows", output);
      assertTypeFailure(() -> compile(operations.select(derived.column(output)).from(derived)));
    }
    var added = Sql.add(TABLE.number, Sql.value(1));
    assertTypeFailure(() -> compile(operations.select(TABLE.id).from(TABLE).where(added.eq(5))));
    assertTypeFailure(() -> compile(operations.select(TABLE.id).from(TABLE)
        .where(added.eq(5).and(TABLE.id.in(List.of())))));
  }

  @Test
  void reusedParameterChecksEveryStandardSourceInBothOrdersBeforePruningAndCount() {
    var operations = operations(true);
    var parameter = Sql.parameter(Integer.class);
    var view = Sql.value(parameter);
    var custom = Sql.add(TABLE.number, Sql.value(1));
    assertTypeFailure(() -> compile(operations.select(view).from(TABLE)
        .where(custom.eq(parameter)).bind(parameter, 5)));
    for (boolean viewFirst : List.of(true, false)) {
      var condition = viewFirst
          ? view.eq(5).and(custom.eq(parameter))
          : custom.eq(parameter).and(view.eq(5));
      for (var query : List.of(
          operations.select(TABLE.id).from(TABLE).where(condition).bind(parameter, 5),
          operations.select(TABLE.id).from(TABLE)
              .where(condition.and(TABLE.id.in(List.of()))).bind(parameter, 5))) {
        assertTypeFailure(() -> compile(query));
        assertTypeFailure(() -> compileCount(query));
      }
      // Replacing the standard binder with a physical built-in source must not discard it either.
      var physicalParameter = Sql.parameter(Long.class);
      var customLong = Sql.cast(TABLE.number, Long.class, SqlType.BIGINT);
      var withPhysicalSource = viewFirst
          ? customLong.eq(physicalParameter).and(TABLE.id.eq(physicalParameter))
          : TABLE.id.eq(physicalParameter).and(customLong.eq(physicalParameter));
      assertTypeFailure(() -> compile(operations.select(TABLE.id).from(TABLE)
          .where(withPhysicalSource).bind(physicalParameter, 5L)));
    }
  }

  @Test
  void validStandardParameterReuseKeepsOneLogicalSlotAndIsolatedRuntimeMappings() {
    var parameter = Sql.parameter(Integer.class);
    var view = Sql.value(parameter);
    var computed = Sql.add(TABLE.number, Sql.value(1));
    var operations = operations(false);
    for (boolean viewFirst : List.of(true, false)) {
      var condition = viewFirst
          ? view.eq(5).and(computed.eq(parameter))
          : computed.eq(parameter).and(view.eq(5));
      var query = operations.select(TABLE.id).from(TABLE).where(condition).bind(parameter, 5);
      var compiled = compile(query);
      assertEquals(4, compiled.plan().parameterCount());
      // Both physical occurrences of p retain the same logical descriptor after mapping validation.
      assertEquals(3, compiled.plan().renderedSql().parameters().stream()
          .map(slot -> slot.ordinal()).distinct().count());
      compileCount(query);
    }
    compile(operations.select(computed).from(TABLE).where(computed.eq(parameter)).bind(parameter, 5));
    assertTypeFailure(() -> compile(operations(true).select(computed).from(TABLE)
        .where(computed.eq(parameter)).bind(parameter, 5)));
  }

  @Test
  void mappingDiagnosticsIdentifySelectionHavingOrderingAndNestedQueryLocations() {
    var operations = operations(true);
    var sum = Sql.sumAsLong(TABLE.number);
    var selected = assertThrows(QueryValidationException.class,
        () -> compile(operations.select(sum).from(TABLE)));
    assertMappingLocation(selected, "$", "SELECT", "s.number");

    var having = assertThrows(QueryValidationException.class,
        () -> compile(operations.select(Sql.countAll()).from(TABLE)
            .having(sum.eq(Sql.value(987654321L)))));
    assertMappingLocation(having, "$", "HAVING", "s.number");
    assertFalse(having.diagnostics().safeText().contains("987654321"));

    var ordered = assertThrows(QueryValidationException.class,
        () -> compile(operations.select(Sql.countAll()).from(TABLE).orderBy(sum.asc())));
    assertMappingLocation(ordered, "$", "ORDER BY", "s.number");

    var inner = TABLE.as("diagnostic_inner");
    var nested = assertThrows(QueryValidationException.class,
        () -> compile(operations.select(TABLE.id).from(TABLE)
            .where(Sql.exists(Sql.select(Sql.sumAsLong(inner.number)).from(inner)))));
    assertMappingLocation(nested, "$/WHERE[0]#0", "SELECT", "diagnostic_inner.number");

    var scalar = Sql.scalar(Sql.select(inner.number).from(inner));
    var scalarInput = assertThrows(QueryValidationException.class,
        () -> compile(operations.select(Sql.sumAsLong(scalar)).from(TABLE)));
    assertMappingLocation(scalarInput, "$", "SELECT", "diagnostic_inner.number");
  }

  @Test
  void selectedScalarAggregateFailuresKeepInnerLocationsForScalarAndProjectionResults() {
    var operations = operations(true);
    var inner = TABLE.as("selected_inner");
    var scalar = Sql.scalar(Sql.select(Sql.sumAsLong(inner.number)).from(inner));
    for (boolean projection : List.of(false, true)) {
      SelectQuery<? extends @Nullable Object> query = projection
          ? operations.select(summaryMapping().bind(TABLE.id, Sql.value(987654321L),
              Sql.value(BigInteger.ONE), Sql.value(BigDecimal.ONE), scalar)).from(TABLE)
          : operations.select(scalar).from(TABLE);
      String block = projection ? "$/SELECT[4]#0" : "$/SELECT[0]#0";
      for (var candidate : List.of(query, query.where(TABLE.id.in(List.of())))) {
        for (Executable action : List.<Executable>of(
            () -> compile(candidate),
            () -> compileCount(candidate),
            () -> candidate.orderBy(TABLE.id.asc()).fetchSlice(SliceRequest.keysetFirst(2)))) {
          QueryValidationException failure = assertThrows(QueryValidationException.class, action);
          assertMappingLocation(failure, block, "SELECT", "selected_inner.number");
          assertEquals("SELECT[0]",
              Objects.requireNonNull(failure.diagnostics().query()).expressionPath());
          assertFalse(failure.diagnostics().safeText().contains("987654321"));
        }
      }
    }
  }

  @Test
  void boundAggregateFailuresKeepOriginalLocationsForContentCountAndPruning() {
    var operations = operations(true);
    var sum = Sql.sumAsLong(TABLE.number);
    var parameter = Sql.parameter(Long.class);
    for (boolean explicit : List.of(false, true)) {
      var query = operations.select(Sql.countAll()).from(TABLE)
          .having(explicit ? sum.gt(parameter) : sum.gt(987654321L));
      if (explicit) {
        query = query.bind(parameter, 987654321L);
      }
      for (var candidate : List.of(query, query.andHaving(Sql.countAll().in(List.of())))) {
        for (Executable action : List.<Executable>of(
            () -> compile(candidate), () -> compileCount(candidate))) {
          QueryValidationException failure = assertThrows(QueryValidationException.class, action);
          assertMappingLocation(failure, "$", "HAVING", "s.number");
          assertFalse(failure.diagnostics().safeText().contains("987654321"));
        }
      }
    }

    var inner = TABLE.as("bound_inner");
    var innerSum = Sql.sumAsLong(inner.number);
    var description = Sql.select(Sql.countAll()).from(inner).having(innerSum.gt(parameter));
    var nested = operations.select(TABLE.id).from(TABLE).where(Sql.exists(description))
        .bind(parameter, 987654321L);
    for (var candidate : List.of(nested, nested.and(TABLE.id.in(List.of())))) {
      for (Executable action : List.<Executable>of(
          () -> compile(candidate), () -> compileCount(candidate))) {
        QueryValidationException failure = assertThrows(QueryValidationException.class, action);
        assertMappingLocation(failure, "$/WHERE[0]#0", "HAVING", "bound_inner.number");
        assertFalse(failure.diagnostics().safeText().contains("987654321"));
      }
    }
  }

  private static void assertMappingLocation(
      QueryValidationException failure, String block, String clause, String input) {
    assertEquals(SkisErrorCode.QUERY_TYPE_MISMATCH, failure.code());
    var location = Objects.requireNonNull(failure.diagnostics().query());
    assertEquals(block, location.blockPath());
    assertEquals(clause, location.clause());
    assertTrue(Objects.requireNonNull(location.expressionPath()).startsWith(clause + '['));
    var facts = Objects.requireNonNull(failure.diagnostics().facts());
    assertTrue(Objects.requireNonNull(facts.detail()).contains(input));
  }

  @Test
  void ordinaryCountConditionsAndDirectCustomSelectionsRemainAllowed() {
    var registry = registry(true);
    var operations = operations(true);
    compile(operations.select(Sql.count(TABLE.number)).from(TABLE));
    compile(operations.select(Sql.count(Sql.add(TABLE.number, Sql.value(1)))).from(TABLE));
    compile(operations.select(Sql.caseWhen(TABLE.number.eq(7), Sql.value("yes"), Sql.value("no"))).from(TABLE));
    compile(operations.select(Sql.countAll()).from(TABLE).groupBy(TABLE.number));
    compile(operations.select(TABLE.number).from(TABLE).groupBy(TABLE.number));
    assertSame(registry.require(ENTITY).property(NUMBER).codec(),
        ResolvedValueMapping.codecFor(TABLE.number, registry));
  }

  @Test
  void standardNonNullDeclarationsStillHonorOuterJoinNullExtension() {
    var operations = operations(false);
    var right = TABLE.as("right_s");
    var computed = Sql.add(right.id, Sql.value(1L));
    assertEquals(Nullability.NON_NULL, computed.nullability());
    var strict = operations.select(computed).from(TABLE).leftJoin(right).on(TABLE.id.eq(right.id));
    assertEquals(SkisErrorCode.QUERY_NULLABILITY_CONTRACT,
        assertThrows(QueryValidationException.class, () -> compile(strict)).code());
    compile(operations.selectNullable(computed).from(TABLE).leftJoin(right).on(TABLE.id.eq(right.id)));
    compile(operations.select(Sql.coalesce(computed, Sql.value(0L)))
        .from(TABLE).leftJoin(right).on(TABLE.id.eq(right.id)));
  }

  @Test
  void mixedProjectionReadsFixedDomainsByIndexAndKeepsNullAggregateRows() throws Exception {
    var operations = operations(false);
    var inner = TABLE.as("scalar_s");
    var selection = summaryMapping().bind(TABLE.id, Sql.countAll(), Sql.sumAsBigInteger(TABLE.id),
        Sql.avgAsBigDecimal(TABLE.number), Sql.scalar(Sql.select(Sql.countAll()).from(inner)));
    var compiled = compile(operations.select(selection).from(TABLE).groupBy(TABLE.id));
    var large = BigInteger.TEN.pow(40);
    assertEquals(new Summary(7L, 2L, large, new BigDecimal("1.5"), 3L),
        compiled.plan().rowDecoder().decode(resultSet(Map.of(1, 7L, 2, 2L,
            3, new BigDecimal(large), 4, new BigDecimal("1.5"), 5, 3L)), RowReadContext.EMPTY));
    assertEquals(new Summary(7L, 0L, null, null, null),
        compiled.plan().rowDecoder().decode(resultSet(Map.of(1, 7L, 2, 0L)), RowReadContext.EMPTY));
    assertEquals(SkisErrorCode.QUERY_RESULT_MAPPING,
        assertThrows(QueryValidationException.class, () -> compiled.plan().rowDecoder()
            .decode(resultSet(Map.of(1, 7L, 2, 2L, 3, new BigDecimal("1.25"))), RowReadContext.EMPTY)).code());
  }

  @Test
  void nullableAggregatesScalarCountAndOuterJoinedCountCannotSatisfyStrictProjectionParameters() {
    var operations = operations(false);
    var inner = TABLE.as("nullable_s");
    var strict = strictLongMapping();
    for (Selectable<Long> value : List.of(Sql.sumAsLong(TABLE.number),
        Sql.scalar(Sql.select(Sql.countAll()).from(inner)))) {
      assertEquals(SkisErrorCode.QUERY_PROJECTION_MAPPING,
          assertThrows(QueryValidationException.class,
              () -> compile(operations.select(strict.bind(value)).from(TABLE))).code());
    }
    compile(operations.select(strict.bind(Sql.countAll())).from(TABLE));
    var output = Sql.output(Sql.countAll(), "count");
    var derived = Sql.derived(Sql.select(Sql.countAll()).from(inner), "counts", output);
    assertEquals(SkisErrorCode.QUERY_PROJECTION_MAPPING,
        assertThrows(QueryValidationException.class, () -> compile(operations.select(strict.bind(derived.column(output)))
            .from(TABLE).leftJoin(derived).on(TABLE.id.eq(1L)))).code());
  }

  @Test
  void expressionAggregateAndDerivedOrderingRemainUnsupportedForSlices() {
    var operations = operations(false);
    var output = Sql.output(TABLE.id, "id");
    var derived = Sql.derived(Sql.select(TABLE.id).from(TABLE), "keyset_rows", output);
    for (SliceRequest request : List.of(SliceRequest.offset(0, 2), SliceRequest.keysetFirst(2))) {
      for (Selectable<?> ordered : List.of(Sql.add(TABLE.id, Sql.value(1L)), Sql.countAll())) {
        assertEquals(SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED,
            assertThrows(QueryValidationException.class, () -> operations.select(TABLE.id).from(TABLE)
                .orderBy(TABLE.id.asc(), ordered.asc()).fetchSlice(request)).code());
      }
      assertEquals(SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED,
          assertThrows(QueryValidationException.class, () -> operations.select(derived.column(output)).from(derived)
              .orderBy(derived.column(output).asc()).fetchSlice(request)).code());
    }
  }

  private static void assertTypeFailure(Executable operation) {
    assertEquals(SkisErrorCode.QUERY_TYPE_MISMATCH,
        assertThrows(QueryValidationException.class, operation).code());
  }

  private static <R extends @Nullable Object> QueryCompilation<R> compile(SelectQuery<R> query) {
    return ((DefaultSelectQuery<R>) query).compilation(QueryPagination.None.INSTANCE);
  }

  private static <R extends @Nullable Object> QueryCompilation<Long> compileCount(SelectQuery<R> query) {
    return ((DefaultSelectQuery<R>) query).countCompilation();
  }

  private static QueryOperations operations(boolean custom) {
    return QueryTestSupport.operations(QueryRuntime.compile(registry(custom), TestDialect.INSTANCE));
  }

  private static EntityRuntimeRegistry registry(boolean custom) {
    return EntityRuntimeRegistry.of(List.of(new EntityRuntimeModel<>(ENTITY,
        layout -> (result, context) -> { throw new AssertionError("scalar/projection must not decode entities"); },
        List.of(new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(NUMBER, custom ? delegate(JdbcCodecs.INTEGER) : JdbcCodecs.INTEGER),
            new PropertyRuntime<>(LABEL, custom ? delegate(JdbcCodecs.STRING) : JdbcCodecs.STRING)))));
  }

  private static <V> JdbcTypeCodec<V> delegate(JdbcTypeCodec<V> codec) {
    return new JdbcTypeCodec<>() {
      @Override public @Nullable V read(ResultSet result, int index, JdbcReadContext context) throws SQLException {
        return codec.read(result, index, context);
      }
      @Override public void bind(PreparedStatement statement, int index, @Nullable V value, JdbcWriteContext context) throws SQLException {
        codec.bind(statement, index, value, context);
      }
    };
  }

  private static ProjectionMapping<Summary> summaryMapping() {
    return ProjectionMapping.generated(GeneratedModelAbi.CURRENT, Summary.class, "aggregate-summary",
        List.of(new ProjectionMapping.Parameter(0, "key", Long.class, Nullability.NON_NULL, 0),
            new ProjectionMapping.Parameter(1, "count", Long.class, Nullability.NON_NULL, 1),
            new ProjectionMapping.Parameter(2, "sum", BigInteger.class, Nullability.NULLABLE, 2),
            new ProjectionMapping.Parameter(3, "average", BigDecimal.class, Nullability.NULLABLE, 3),
            new ProjectionMapping.Parameter(4, "scalar", Long.class, Nullability.NULLABLE, 4)),
        readers -> {
          ProjectionMapping.ValueReader<Long> key = readers.reader(0, Long.class);
          ProjectionMapping.ValueReader<Long> count = readers.reader(1, Long.class);
          ProjectionMapping.ValueReader<@Nullable BigInteger> sum = readers.reader(2, BigInteger.class);
          ProjectionMapping.ValueReader<@Nullable BigDecimal> average = readers.reader(3, BigDecimal.class);
          ProjectionMapping.ValueReader<@Nullable Long> scalar = readers.reader(4, Long.class);
          return (result, context) -> new Summary(key.read(result, context), count.read(result, context),
              sum.read(result, context), average.read(result, context), scalar.read(result, context));
        });
  }

  private static ProjectionMapping<Long> strictLongMapping() {
    return ProjectionMapping.generated(GeneratedModelAbi.CURRENT, Long.class, "strict-long",
        List.of(new ProjectionMapping.Parameter(0, "value", Long.class, Nullability.NON_NULL, 0)),
        readers -> {
          ProjectionMapping.ValueReader<Long> value = readers.reader(0, Long.class);
          return value::read;
        });
  }

  private static ResultSet resultSet(Map<Integer, Object> values) {
    int[] last = {0};
    return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
        new Class<?>[] {ResultSet.class}, (proxy, method, arguments) -> {
          if (method.getName().equals("wasNull")) return values.get(last[0]) == null;
          assertTrue(arguments != null && arguments[0] instanceof Integer, "read by index only");
          last[0] = (Integer) arguments[0];
          Object value = values.get(last[0]);
          return switch (method.getName()) {
            case "getLong" -> value == null ? 0L : ((Number) value).longValue();
            case "getBigDecimal" -> value;
            default -> throw new AssertionError("unexpected reader " + method.getName());
          };
        });
  }

  private record Sample() {}
  private record Summary(Long key, Long count, @Nullable BigInteger sum,
      @Nullable BigDecimal average, @Nullable Long scalar) {}

  private static final class SampleTable extends QueryTable<Sample> {
    private final NonNullSelectable<Long> id = nonNullQueryColumn(ID);
    private final Selectable<Integer> number = queryColumn(NUMBER);
    private final NonNullSelectable<String> label = nonNullQueryColumn(LABEL);
    private SampleTable(String alias) { super(ENTITY, alias); }
    @Override public SampleTable as(String alias) { return new SampleTable(alias); }
  }

  private enum TestDialect implements Dialect {
    INSTANCE;
    private final SqlRenderer renderer = new StandardSqlRenderer(this);
    @Override public String id() { return "mapping-test"; }
    @Override public IdentifierRules identifierRules() { return StandardIdentifierRules.INSTANCE; }
    @Override public DialectCapabilities capabilities() { return DialectCapabilities.of(DialectFeature.values()); }
    @Override public SqlRenderer renderer() { return renderer; }
  }
}
