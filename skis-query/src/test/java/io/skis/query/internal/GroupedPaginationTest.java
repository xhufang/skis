package io.skis.query.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.mapping.spi.RowReadContext;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.NonNullSelectable;
import io.skis.query.PageRequest;
import io.skis.query.QueryOperations;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.SelectQuery;
import io.skis.query.SliceRequest;
import io.skis.query.spi.ProjectionMapping;
import io.skis.query.Sql;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlType;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class GroupedPaginationTest {

  private static final PropertyMeta<Item, Long> ID =
      new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
  private static final PropertyMeta<Item, String> CATEGORY =
      new PropertyMeta<>(1, "category", String.class, ColumnMeta.of("category", true));
  private static final EntityMeta<Item> META = EntityMeta.simple(Item.class,
      new TableMeta("", "", "items"), List.of(ID, CATEGORY),
      new PrimaryKeyMeta<>(List.of(ID)), false);
  private static final ItemTable TABLE = new ItemTable();

  @Test
  void globalAggregateCountsFinalRowsAndPermitsUnorderedOffsetIncludingDistinct() {
    var operations = operations();
    var query = operations.select(Sql.countAll()).from(TABLE).where(TABLE.id.lt(0L));
    String sql = count(query).plan().sql();
    assertEquals("SELECT COUNT(*) FROM (SELECT COUNT(*) AS \"__skis_count_0\" "
        + "FROM \"items\" WHERE \"items\".\"id\" < ?) AS \"__skis_count\"", sql);
    assertEquals(List.of(0L), arguments(count(query)));
    assertReachesJdbc(() -> query.fetchPage(PageRequest.page(0, 2)));
    assertReachesJdbc(() -> query.distinct().fetchSlice(SliceRequest.offset(2, 2)));
    assertEquals(SkisErrorCode.QUERY_PAGINATION_ORDER_REQUIRED,
        assertThrows(QueryValidationException.class,
            () -> operations.selectFrom(TABLE).fetchPage(PageRequest.page(0, 2))).code());
    assertEquals(SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED,
        assertThrows(QueryValidationException.class,
            () -> query.fetchSlice(SliceRequest.keysetFirst(2))).code());
  }

  @Test
  void groupedOrderingUsesWholeResolvedKeysInsteadOfDetailPrimaryKeys() {
    var operations = operations();
    var key = Sql.concat(TABLE.category, Sql.value("!"));
    var query = operations.select(key).from(TABLE).groupBy(key).orderBy(key.asc());
    assertReachesJdbc(() -> query.fetchSlice(SliceRequest.offset(0, 2)));
    assertTrue(count(query).plan().sql().contains("GROUP BY"));
    var aggregateOnlyOrder = operations.select(Sql.countAll()).from(TABLE)
        .groupBy(TABLE.category).orderBy(Sql.countAll().desc());
    assertEquals(SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
        assertThrows(QueryValidationException.class,
            () -> aggregateOnlyOrder.fetchPage(PageRequest.page(0, 2))).code());
    var complete = aggregateOnlyOrder.orderBy(Sql.countAll().desc(), TABLE.category.asc());
    assertReachesJdbc(() -> complete.fetchSlice(SliceRequest.offset(0, 2)));
  }

  @Test
  void distinctAfterGroupingRequiresOnlyTheFinalVisibleTuple() {
    var count = Sql.countAll();
    var query = operations().select(count).from(TABLE).groupBy(TABLE.category)
        .distinct().orderBy(count.asc());
    assertReachesJdbc(() -> query.fetchPage(PageRequest.page(0, 1)));
    assertTrue(count(query).plan().sql().startsWith("SELECT COUNT(*) FROM (SELECT DISTINCT COUNT(*)"));
  }

  @Test
  void groupedDistinctConstantsAllowUnorderedOffsetButGroupedAggregatesStillNeedOrdering() {
    var operations = operations();
    var query = operations.select(Sql.value(7L)).from(TABLE).groupBy(TABLE.category).distinct();
    assertReachesJdbc(() -> query.fetchPage(PageRequest.page(0, 2)));
    assertReachesJdbc(() -> query.fetchSlice(SliceRequest.offset(0, 2)));
    assertReachesJdbc(() -> query.having(Sql.countAll().gt(1L)).fetchPage(PageRequest.page(0, 2)));
    assertTrue(count(query).plan().sql().contains("SELECT DISTINCT ?"));
    assertTrue(count(query).plan().sql().contains("GROUP BY"));
    assertEquals(List.of(7L), arguments(count(query)));
    for (Selectable<Long> aggregate : List.<Selectable<Long>>of(
        Sql.countAll(), Sql.add(Sql.countAll(), Sql.value(1L)))) {
      var grouped = operations.select(aggregate).from(TABLE).groupBy(TABLE.category).distinct();
      assertEquals(SkisErrorCode.QUERY_PAGINATION_ORDER_REQUIRED,
          assertThrows(QueryValidationException.class,
              () -> grouped.fetchPage(PageRequest.page(0, 2))).code());
    }
  }

  @Test
  void constantAndMixedGroupingKeepTheirShapeWithoutExtraOrderingRequirements() {
    var operations = operations();
    var constant = Sql.value("all");
    var singleton = operations.select(Sql.countAll()).from(TABLE).groupBy(constant);
    assertReachesJdbc(() -> singleton.fetchSlice(SliceRequest.offset(0, 2)));
    assertTrue(count(singleton).plan().sql().contains("GROUP BY"));
    var mixed = operations.select(TABLE.category).from(TABLE).groupBy(constant, TABLE.category)
        .orderBy(TABLE.category.asc());
    assertReachesJdbc(() -> mixed.fetchSlice(SliceRequest.offset(0, 2)));
  }

  @Test
  void countRetainsHavingButDropsPresentationOrderingAndPaginationBindings() {
    var suffix = Sql.value("!");
    var query = operations().select(Sql.concat(TABLE.category, suffix)).from(TABLE)
        .where(TABLE.id.gt(4L)).groupBy(TABLE.category).having(Sql.countAll().gt(2L))
        .orderBy(Sql.concat(TABLE.category, Sql.value("?")).asc(), TABLE.category.asc());
    var count = count(query);
    assertTrue(count.plan().sql().contains("HAVING COUNT(*) > ?"));
    assertFalse(count.plan().sql().contains("ORDER BY"));
    assertFalse(count.plan().sql().contains("LIMIT"));
    assertFalse(count.plan().sql().contains("OFFSET"));
    assertEquals(List.of("!", 4L, 2L), arguments(count));
    var content = ((DefaultSelectQuery<?>) query).compilation(new QueryPagination.Offset(3, 9));
    assertTrue(arguments(content).contains("?"));
    assertTrue(arguments(content).contains(9L));
  }

  @Test
  void aggregateOnlyInOrderingKeepsItsSingleRowSemanticsDuringCount() {
    var query = operations().select(Sql.value(7L)).from(TABLE).orderBy(Sql.countAll().asc());
    var count = count(query);
    assertTrue(count.plan().sql().contains("ORDER BY COUNT(*) ASC"));
    assertTrue(count.plan().sql().startsWith("SELECT COUNT(*) FROM (SELECT ?"));
    assertEquals(List.of(7L), arguments(count));
  }

  @Test
  void derivedIdentityRequiresCompleteUnchangedExportsAndRejectsKeyset() {
    var operations = operations();
    var output = Sql.output(TABLE.id, "id");
    var derived = Sql.derived(Sql.select(TABLE.id).from(TABLE), "rows", output);
    var query = operations.select(derived.column(output)).from(derived)
        .orderBy(derived.column(output).asc());
    assertReachesJdbc(() -> query.fetchSlice(SliceRequest.offset(0, 2)));
    assertEquals(SkisErrorCode.QUERY_PAGINATION_UNSUPPORTED,
        assertThrows(QueryValidationException.class,
            () -> query.fetchSlice(SliceRequest.keysetFirst(2))).code());
    var partial = Sql.derived(Sql.select(TABLE.id).from(TABLE).groupBy(TABLE.id, TABLE.category),
        "partial", output);
    assertEquals(SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
        assertThrows(QueryValidationException.class,
            () -> operations.select(partial.column(output)).from(partial)
                .orderBy(partial.column(output).asc()).fetchSlice(SliceRequest.offset(0, 2))).code());
    var cast = Sql.cast(TABLE.id, Long.class, SqlType.BIGINT);
    var castOutput = Sql.output(cast, "cast_id");
    var castRows = Sql.derived(Sql.select(cast).from(TABLE), "cast_rows", castOutput);
    assertEquals(SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
        assertThrows(QueryValidationException.class,
            () -> operations.select(castRows.column(castOutput)).from(castRows)
                .orderBy(castRows.column(castOutput).asc())
                .fetchSlice(SliceRequest.offset(0, 2))).code());
  }

  @Test
  void repeatedDerivedGroupingKeysCanOrderByEitherAliasAndReexportTheSecond() {
    var tuple = ProjectionMapping.generated(GeneratedModelAbi.CURRENT, Object.class, "repeated-key",
        List.of(new ProjectionMapping.Parameter(0, "first", String.class, Nullability.NULLABLE, 0),
            new ProjectionMapping.Parameter(1, "second", String.class, Nullability.NULLABLE, 1)),
        readers -> (rows, context) -> new Object());
    var firstOutput = Sql.output(TABLE.category, "first_key");
    var secondOutput = Sql.output(TABLE.category, "second_key");
    var derived = Sql.derived(Sql.select(tuple.bind(TABLE.category, TABLE.category)).from(TABLE)
        .groupBy(TABLE.category), "repeated_keys", firstOutput, secondOutput);
    var first = derived.column(firstOutput);
    var second = derived.column(secondOutput);
    var operations = operations();
    for (var ordered : List.of(first, second)) {
      var query = operations.select(second).from(derived).orderBy(ordered.asc());
      assertReachesJdbc(() -> query.fetchPage(PageRequest.page(0, 2)));
      assertReachesJdbc(() -> query.fetchSlice(SliceRequest.offset(0, 2)));
    }
    var detail = TABLE.as("detail");
    var joined = operations.select(second).from(derived).crossJoin(detail)
        .orderBy(second.asc(), detail.id.asc());
    assertReachesJdbc(() -> joined.fetchSlice(SliceRequest.offset(0, 2)));
    var reexport = Sql.output(second, "key");
    var outer = Sql.derived(Sql.select(second).from(derived), "outer_keys", reexport);
    var query = operations.select(outer.column(reexport)).from(outer)
        .orderBy(outer.column(reexport).asc());
    assertReachesJdbc(() -> query.fetchPage(PageRequest.page(0, 2)));
  }

  @Test
  void repeatedDistinctScalarOutputsSupportEitherOrderingAliasAndReexport() {
    var operations = operations();
    var inner = TABLE.as("repeated_inner");
    var ceiling = Sql.parameter(Long.class, "ceiling");
    var scalar = Sql.scalar(Sql.select(inner.id).from(inner)
        .where(inner.id.eq(TABLE.id)).and(inner.id.lt(ceiling)));
    var tuple = ProjectionMapping.generated(GeneratedModelAbi.CURRENT, Object.class, "repeated-scalar",
        List.of(new ProjectionMapping.Parameter(0, "first", Long.class, Nullability.NULLABLE, 0),
            new ProjectionMapping.Parameter(1, "second", Long.class, Nullability.NULLABLE, 1)),
        readers -> (rows, context) -> new Object());
    var detail = operations.select(tuple.bind(scalar, scalar)).from(TABLE).bind(ceiling, 10L);
    assertEquals(List.of(10L, 10L), arguments(((DefaultSelectQuery<?>) detail)
        .compilation(QueryPagination.None.INSTANCE)));
    assertEquals(List.of(10L), arguments(((DefaultSelectQuery<?>) detail.distinct())
        .compilation(QueryPagination.None.INSTANCE)));
    for (Selectable<Long> expression : List.<Selectable<Long>>of(
        scalar, Sql.cast(scalar, Long.class, SqlType.BIGINT))) {
      var firstOutput = Sql.output(expression, "first_value");
      var secondOutput = Sql.output(expression, "second_value");
      var derived = Sql.derived(Sql.select(tuple.bind(expression, expression)).from(TABLE).distinct(),
          "repeated_values", firstOutput, secondOutput);
      var first = derived.column(firstOutput);
      var second = derived.column(secondOutput);
      for (var ordered : List.of(first, second)) {
        var query = operations.select(second).from(derived).orderBy(ordered.asc()).bind(ceiling, 10L);
        assertReachesJdbc(() -> query.fetchPage(PageRequest.page(0, 2)));
        assertReachesJdbc(() -> query.fetchSlice(SliceRequest.offset(0, 2)));
        assertEquals(List.of(10L), arguments(count(query)));
      }
      var reexport = Sql.output(second, "value");
      var outer = Sql.derived(Sql.select(second).from(derived), "reexported_values", reexport);
      var query = operations.select(outer.column(reexport)).from(outer)
          .orderBy(outer.column(reexport).asc()).bind(ceiling, 10L);
      assertReachesJdbc(() -> query.fetchPage(PageRequest.page(0, 2)));
      assertReachesJdbc(() -> query.fetchSlice(SliceRequest.offset(0, 2)));
    }
    var independent = Sql.parameter(Long.class, "ceiling");
    var otherScalar = Sql.scalar(Sql.select(inner.id).from(inner)
        .where(inner.id.eq(TABLE.id)).and(inner.id.lt(independent)));
    var firstOutput = Sql.output(scalar, "first_value");
    var secondOutput = Sql.output(otherScalar, "second_value");
    var derived = Sql.derived(Sql.select(tuple.bind(scalar, otherScalar)).from(TABLE).distinct(),
        "independent_values", firstOutput, secondOutput);
    var incomplete = operations.select(derived.column(secondOutput)).from(derived)
        .orderBy(derived.column(secondOutput).asc()).bind(ceiling, 10L).bind(independent, 10L);
    assertEquals(SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
        assertThrows(QueryValidationException.class,
            () -> incomplete.fetchSlice(SliceRequest.offset(0, 2))).code());
    assertReachesJdbc(() -> incomplete.orderBy(derived.column(firstOutput).asc(), derived.column(secondOutput).asc())
        .fetchPage(PageRequest.page(0, 2)));
  }

  @Test
  void distinctOverRepeatedDerivedOutputsOrdersEitherAliasAndReexportsOne() {
    var tuple = ProjectionMapping.generated(GeneratedModelAbi.CURRENT, Object.class, "distinct-aliases",
        List.of(new ProjectionMapping.Parameter(0, "first", String.class, Nullability.NULLABLE, 0),
            new ProjectionMapping.Parameter(1, "second", String.class, Nullability.NULLABLE, 1)),
        readers -> (rows, context) -> new Object());
    var firstOutput = Sql.output(TABLE.category, "first_key");
    var secondOutput = Sql.output(TABLE.category, "second_key");
    for (var description : List.of(
        Sql.select(tuple.bind(TABLE.category, TABLE.category)).from(TABLE).groupBy(TABLE.category),
        Sql.select(tuple.bind(TABLE.category, TABLE.category)).from(TABLE).groupBy(TABLE.id, TABLE.category))) {
      var derived = Sql.derived(description, "duplicate_keys", firstOutput, secondOutput);
      var first = derived.column(firstOutput);
      var second = derived.column(secondOutput);
      var operations = operations();
      for (var ordered : List.of(first, second)) {
        var query = operations.select(tuple.bind(first, second)).from(derived).distinct()
            .orderBy(ordered.asc());
        assertReachesJdbc(() -> query.fetchPage(PageRequest.page(0, 2)));
        assertReachesJdbc(() -> query.fetchSlice(SliceRequest.offset(0, 2)));
        assertTrue(count(query).plan().sql().startsWith("SELECT COUNT(*) FROM (SELECT DISTINCT "));
      }
      var outerFirst = Sql.output(first, "first_key");
      var outerSecond = Sql.output(second, "second_key");
      var distinct = Sql.derived(Sql.select(tuple.bind(first, second)).from(derived).distinct(),
          "distinct_keys", outerFirst, outerSecond);
      var chosen = distinct.column(outerSecond);
      var valueOutput = Sql.output(chosen, "key");
      var reexported = Sql.derived(Sql.select(chosen).from(distinct), "one_key", valueOutput);
      var value = reexported.column(valueOutput);
      var query = operations.select(value).from(reexported).orderBy(value.asc());
      assertReachesJdbc(() -> query.fetchPage(PageRequest.page(0, 2)));
      assertReachesJdbc(() -> query.fetchSlice(SliceRequest.offset(0, 2)));
    }
  }

  @Test
  void distinctAliasEquivalenceKeepsLogicalParametersAndSourceOccurrencesSeparate() {
    var pair = ProjectionMapping.generated(GeneratedModelAbi.CURRENT, Object.class, "alias-pair",
        List.of(new ProjectionMapping.Parameter(0, "first", String.class, Nullability.NULLABLE, 0),
            new ProjectionMapping.Parameter(1, "second", String.class, Nullability.NULLABLE, 1)),
        readers -> (rows, context) -> new Object());
    var firstOutput = Sql.output(TABLE.category, "first_key");
    var secondOutput = Sql.output(TABLE.category, "second_key");
    var derived = Sql.derived(Sql.select(pair.bind(TABLE.category, TABLE.category)).from(TABLE)
        .groupBy(TABLE.category), "duplicate_keys", firstOutput, secondOutput);
    var first = derived.column(firstOutput);
    var second = derived.column(secondOutput);
    var inner = TABLE.as("scalar_items");
    var ceilingA = Sql.parameter(Long.class, "ceiling");
    var ceilingB = Sql.parameter(Long.class, "ceiling");
    var scalarA = Sql.scalar(Sql.select(inner.id).from(inner)
        .where(inner.category.eq(first)).and(inner.id.lt(ceilingA)));
    var scalarB = Sql.scalar(Sql.select(inner.id).from(inner)
        .where(inner.category.eq(first)).and(inner.id.lt(ceilingB)));
    var tuple = ProjectionMapping.generated(GeneratedModelAbi.CURRENT, Object.class, "aliases-and-scalars",
        List.of(new ProjectionMapping.Parameter(0, "first", String.class, Nullability.NULLABLE, 0),
            new ProjectionMapping.Parameter(1, "second", String.class, Nullability.NULLABLE, 1),
            new ProjectionMapping.Parameter(2, "a", Long.class, Nullability.NULLABLE, 2),
            new ProjectionMapping.Parameter(3, "b", Long.class, Nullability.NULLABLE, 3)),
        readers -> (rows, context) -> new Object());
    var operations = operations();
    var query = operations.select(tuple.bind(first, second, scalarA, scalarB)).from(derived)
        .distinct().orderBy(second.asc(), scalarA.asc()).bind(ceilingA, 10L).bind(ceilingB, 10L);
    assertEquals(SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
        assertThrows(QueryValidationException.class,
            () -> query.fetchPage(PageRequest.page(0, 2))).code());
    var complete = query.orderBy(second.asc(), scalarA.asc(), scalarB.asc());
    assertReachesJdbc(() -> complete.fetchPage(PageRequest.page(0, 2)));
    assertReachesJdbc(() -> complete.fetchSlice(SliceRequest.offset(0, 2)));

    var other = derived.as("other_keys");
    var otherKey = other.column(secondOutput);
    var cross = operations.select(pair.bind(first, otherKey)).from(derived).crossJoin(other)
        .distinct().orderBy(otherKey.asc());
    assertEquals(SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
        assertThrows(QueryValidationException.class,
            () -> cross.fetchSlice(SliceRequest.offset(0, 2))).code());
    assertReachesJdbc(() -> cross.orderBy(first.asc(), otherKey.asc())
        .fetchPage(PageRequest.page(0, 2)));
  }

  @Test
  void derivedFullJoinKeepsItsNonAllNullTupleWhenJoinedToNullableGroups() {
    var left = TABLE.as("left_items");
    var right = TABLE.as("right_items");
    var tuple = ProjectionMapping.generated(GeneratedModelAbi.CURRENT, Object.class, "full-join-key",
        List.of(new ProjectionMapping.Parameter(0, "left", Long.class, Nullability.NULLABLE, 0),
            new ProjectionMapping.Parameter(1, "right", Long.class, Nullability.NULLABLE, 1)),
        readers -> (rows, context) -> new Object());
    var leftOutput = Sql.outputNullable(left.id, "left_id");
    var rightOutput = Sql.outputNullable(right.id, "right_id");
    var rows = Sql.derived(Sql.select(tuple.bind(left.id, right.id)).from(left)
        .fullJoin(right).on(left.id.eq(right.id)), "joined_items", leftOutput, rightOutput);
    var groupOutput = Sql.output(TABLE.category, "category");
    var groups = Sql.derived(Sql.select(TABLE.category).from(TABLE).groupBy(TABLE.category),
        "categories", groupOutput);
    var leftId = rows.column(leftOutput);
    var rightId = rows.column(rightOutput);
    var category = groups.column(groupOutput);
    var query = operations().select(leftId).from(rows).fullJoin(groups).on(category.isNull())
        .orderBy(leftId.asc(), rightId.asc(), category.asc());
    assertReachesJdbc(() -> query.fetchPage(PageRequest.page(0, 2)));
    assertReachesJdbc(() -> query.fetchSlice(SliceRequest.offset(0, 2)));
    assertEquals(SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
        assertThrows(QueryValidationException.class, () -> query.orderBy(leftId.asc(), category.asc())
            .fetchSlice(SliceRequest.offset(0, 2))).code());
  }

  @Test
  void multiColumnDistinctCountDoesNotExecuteItsProjectionDecoder() throws Exception {
    var tuple = ProjectionMapping.generated(GeneratedModelAbi.CURRENT, Object.class, "count-only",
        List.of(new ProjectionMapping.Parameter(0, "id", Long.class, Nullability.NON_NULL, 0),
            new ProjectionMapping.Parameter(1, "category", String.class, Nullability.NULLABLE, 1)),
        readers -> (rows, context) -> { throw new AssertionError("inner decoder executed"); });
    var query = operations().select(tuple.bind(TABLE.id, TABLE.category)).from(TABLE).distinct();
    var count = count(query);
    assertTrue(count.plan().sql().startsWith("SELECT COUNT(*) FROM (SELECT DISTINCT "));
    var rows = (java.sql.ResultSet) java.lang.reflect.Proxy.newProxyInstance(
        GroupedPaginationTest.class.getClassLoader(), new Class<?>[] {java.sql.ResultSet.class},
        (proxy, method, values) -> switch (method.getName()) {
          case "getLong" -> { assertEquals(1, values[0]); yield 3L; }
          case "wasNull" -> false;
          default -> throw new AssertionError("unexpected count reader call: " + method.getName());
        });
    assertEquals(3L, count.plan().rowDecoder().decode(rows, RowReadContext.EMPTY));
  }

  @Test
  void offsetOrderingSignaturesIncludeExpressionStructureAndParameterIdentity() {
    var operations = operations();
    var a = Sql.parameter(String.class, "a");
    var b = Sql.parameter(String.class, "b");
    var keyA = Sql.concat(TABLE.category, Sql.value(a));
    var keyB = Sql.concat(TABLE.category, Sql.value(b));
    var first = (DefaultSelectQuery<?>) operations.select(keyA).from(TABLE).groupBy(keyA)
        .orderBy(keyA.asc()).bind(a, "!");
    var rebound = (DefaultSelectQuery<?>) operations.select(keyA).from(TABLE).groupBy(keyA)
        .orderBy(keyA.asc()).bind(a, "?");
    assertEquals(first.orderSignature(), rebound.orderSignature());
    assertEquals(first.queryFingerprint(), rebound.queryFingerprint());
    var wrong = operations.select(keyA).from(TABLE).groupBy(keyA).orderBy(keyB.asc())
        .bind(a, "!").bind(b, "!");
    assertEquals(SkisErrorCode.QUERY_GROUPING_CONTRACT,
        assertThrows(QueryValidationException.class,
            () -> wrong.fetchSlice(SliceRequest.offset(0, 1))).code());
  }

  @Test
  void wrappedCountAllocatesAnAliasOutsideAllOriginalSourceQualifiers() {
    var table = TABLE.as("__skis_count");
    var query = operations().select(Sql.countAll()).from(table);
    assertTrue(count(query).plan().sql().endsWith(
        "FROM \"items\" AS \"__skis_count\") AS \"__skis_count_0\""));
  }

  @Test
  void distinctCorrelatedScalarOrderingRetainsLogicalParametersAndRequiresTheWholeTuple() {
    var operations = operations();
    var inner = TABLE.as("inner_rows");
    var ceiling = Sql.parameter(Long.class, "ceiling");
    var scalar = Sql.scalar(Sql.select(inner.id).from(inner)
        .where(inner.id.eq(TABLE.id)).and(inner.id.lt(ceiling)));
    var query = operations.select(scalar).from(TABLE).distinct().orderBy(scalar.asc())
        .bind(ceiling, 10L);
    assertReachesJdbc(() -> query.fetchPage(PageRequest.page(0, 2)));
    assertReachesJdbc(() -> query.fetchSlice(SliceRequest.offset(0, 2)));
    assertEquals(List.of(10L), arguments(count(query)));
    var computed = Sql.coalesce(scalar, Sql.value(-1L));
    var expressionQuery = operations.select(computed).from(TABLE).distinct()
        .orderBy(computed.asc()).bind(ceiling, 10L);
    assertReachesJdbc(() -> expressionQuery.fetchSlice(SliceRequest.offset(0, 2)));

    var tuple = ProjectionMapping.generated(GeneratedModelAbi.CURRENT, Object.class, "scalar-tuple",
        List.of(new ProjectionMapping.Parameter(0, "id", Long.class, Nullability.NON_NULL, 0),
            new ProjectionMapping.Parameter(1, "scalar", Long.class, Nullability.NULLABLE, 1)),
        readers -> (rows, context) -> new Object());
    var incomplete = operations.select(tuple.bind(TABLE.id, scalar)).from(TABLE).distinct()
        .orderBy(TABLE.id.asc()).bind(ceiling, 10L);
    assertEquals(SkisErrorCode.QUERY_PAGINATION_UNSTABLE_ORDER,
        assertThrows(QueryValidationException.class,
            () -> incomplete.fetchSlice(SliceRequest.offset(0, 2))).code());
    assertReachesJdbc(() -> incomplete.orderBy(TABLE.id.asc(), scalar.asc())
        .fetchSlice(SliceRequest.offset(0, 2)));

    var other = Sql.parameter(Long.class, "ceiling");
    var otherScalar = Sql.scalar(Sql.select(inner.id).from(inner)
        .where(inner.id.eq(TABLE.id)).and(inner.id.lt(other)));
    assertThrows(QueryValidationException.class, () -> query.orderBy(otherScalar.asc())
        .bind(other, 10L).fetchSlice(SliceRequest.offset(0, 2)));
  }

  @Test
  void distinctScalarOrderingStillValidatesEveryOriginalSourceOccurrence() {
    var outer = TABLE.as("outer_rows");
    var impostor = TABLE.as("outer_rows");
    var inner = TABLE.as("inner_rows");
    var selected = Sql.scalar(Sql.select(inner.id).from(inner).where(inner.id.eq(outer.id)));
    var ordered = Sql.scalar(Sql.select(inner.id).from(inner).where(inner.id.eq(impostor.id)));
    var query = operations().select(selected).from(outer).distinct().orderBy(ordered.asc());
    assertThrows(QueryValidationException.class,
        () -> query.fetchSlice(SliceRequest.offset(0, 2)));
  }

  @Test
  void paginationReusesTheOriginalResolvedStageForContentOrderedAndCountCompilation() {
    for (boolean keyset : List.of(false, true)) {
      var dialect = new RecordingDialect();
      var query = operations(dialect).select(TABLE.id).from(TABLE)
          .where(TABLE.id.in(List.of())).orderBy(TABLE.id.asc());
      if (keyset) {
        assertReachesJdbc(() -> query.fetchSlice(SliceRequest.keysetFirst(2)));
        count(query);
      } else {
        assertReachesJdbc(() -> query.fetchPage(PageRequest.page(0, 2)));
      }
      var originals = java.util.Collections.newSetFromMap(
          new java.util.IdentityHashMap<ResolvedStatement, Boolean>());
      int validations = 0;
      boolean paginated = false;
      boolean counted = false;
      for (ResolvedStatement stage : dialect.validated) {
        if (stage.statement() instanceof SelectStatement select) {
          if (select.pagination().isEmpty()) {
            originals.add(stage);
            validations++;
          } else {
            paginated = true;
          }
        } else if (stage.statement() instanceof io.skis.sql.ast.CountAst) {
          counted = true;
        }
      }
      assertEquals(1, originals.size());
      assertTrue(validations >= 3, "source mapping and dialect validation must still execute");
      assertTrue(paginated, "the final paginated statement must be separately analyzed");
      assertTrue(counted, "the final count statement must be separately analyzed");
    }
  }

  @Test
  void rowIndependentDistinctCanUseUnorderedOffsetButStillRequiresOrderingForKeyset() {
    var query = operations().select(Sql.value(7L)).from(TABLE).distinct();
    assertReachesJdbc(() -> query.fetchSlice(SliceRequest.offset(0, 2)));
    assertEquals(SkisErrorCode.QUERY_PAGINATION_ORDER_REQUIRED,
        assertThrows(QueryValidationException.class,
            () -> query.fetchSlice(SliceRequest.keysetFirst(2))).code());
  }

  private static QueryCompilation<Long> count(SelectQuery<?> query) {
    return ((DefaultSelectQuery<?>) query).countCompilation();
  }

  private static List<@Nullable Object> arguments(QueryCompilation<?> query) {
    return (QueryArguments) query.argument();
  }

  private static void assertReachesJdbc(org.junit.jupiter.api.function.Executable operation) {
    assertEquals("compilation must not acquire JDBC",
        assertThrows(AssertionError.class, operation).getMessage());
  }

  private static QueryOperations operations() {
    return operations(TestDialect.INSTANCE);
  }

  private static QueryOperations operations(Dialect dialect) {
    var model = new EntityRuntimeModel<>(META,
        layout -> (rows, context) -> new Item(0L, null),
        List.of(new PropertyRuntime<>(ID, JdbcCodecs.LONG),
            new PropertyRuntime<>(CATEGORY, JdbcCodecs.STRING)));
    return QueryTestSupport.operations(QueryRuntime.compile(
        EntityRuntimeRegistry.of(List.of(model)), dialect));
  }

  private record Item(Long id, @Nullable String category) {}

  private static final class ItemTable extends QueryTable<Item> {
    private final NonNullSelectable<Long> id = nonNullQueryColumn(ID);
    private final Selectable<String> category = nullableQueryColumn(CATEGORY);
    private ItemTable() { super(META); }
    private ItemTable(String alias) { super(META, alias); }
    @Override public ItemTable as(String alias) { return new ItemTable(alias); }
  }

  private enum TestDialect implements Dialect {
    INSTANCE;
    private final DialectCapabilities capabilities = DialectCapabilities.of(DialectFeature.values());
    private final SqlRenderer renderer = new StandardSqlRenderer(this);
    @Override public String id() { return "grouped-pagination-test"; }
    @Override public IdentifierRules identifierRules() { return StandardIdentifierRules.INSTANCE; }
    @Override public DialectCapabilities capabilities() { return capabilities; }
    @Override public SqlRenderer renderer() { return renderer; }
  }

  private static final class RecordingDialect implements Dialect {
    private final List<ResolvedStatement> validated = new java.util.ArrayList<>();
    private final SqlRenderer renderer = new StandardSqlRenderer(this);
    @Override public String id() { return "pagination-stage-reuse"; }
    @Override public IdentifierRules identifierRules() { return StandardIdentifierRules.INSTANCE; }
    @Override public DialectCapabilities capabilities() { return TestDialect.INSTANCE.capabilities(); }
    @Override public SqlRenderer renderer() { return renderer; }
    @Override public void validate(ResolvedStatement stage) {
      validated.add(stage);
      Dialect.super.validate(stage);
    }
  }
}
