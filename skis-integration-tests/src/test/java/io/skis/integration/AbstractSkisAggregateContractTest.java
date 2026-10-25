package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.spi.ExecutionContext;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.support.SqlCompiler;
import io.skis.dialect.support.SqlTemplate;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mapping.spi.JdbcCodecs;
import io.skis.mapping.spi.JdbcReadContext;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.Sql;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import io.skis.sql.ast.AggregateExpression;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.ArithmeticOperator;
import io.skis.sql.ast.BetweenPredicate;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.CaseExpression;
import io.skis.sql.ast.CaseWhen;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.CoalesceExpression;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.FromClause;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.JoinClause;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.LikePredicate;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.OrderDirection;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.TableExpression;
import io.skis.testmodel.pet.skis.AggregateIntegerResultPro;
import io.skis.testmodel.pet.skis.AggregateSampleTable;
import io.skis.testmodel.pet.skis.AggregateSummaryPro;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Shared database contracts for aggregate arithmetic, metadata and generated result mapping. */
abstract class AbstractSkisAggregateContractTest {

  private static final BooleanPredicate FALSE = new BooleanPredicate(false);
  private static final BigInteger LARGE_INTEGER = BigInteger.TEN.pow(45).add(BigInteger.valueOf(7));
  private static final BigDecimal PRECISE = new BigDecimal("123456789012345678901234567890.123456789012345678901234567890");

  protected abstract DataSource createDataSource();

  protected abstract Dialect dialect();

  @Test
  void generatedAggregateProjectionReadsGroupingWideningDecimalsAndScalarCount() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      createRuntimeSamples(connection);
      SkisExecutor executor = executor(source, connection);
      var table = AggregateSampleTable.aggregateSampleTable;
      var inner = table.as("inner_sample");
      var label = Sql.concat(table.category(), Sql.value("!"));
      var scalar = Sql.scalar(Sql.select(Sql.countAll()).from(inner));
      var query = executor.select(AggregateSummaryPro.of(label, Sql.countAll(),
              Sql.sumAsBigInteger(table.id()), Sql.avgAsBigDecimal(table.amount()), scalar))
          .from(table).groupBy(table.category()).orderBy(table.category().asc());
      var rows = query.fetchList();
      assertEquals(List.of("A!", "B!"), rows.stream().map(row -> row.category()).toList());
      assertEquals(2, rows.getFirst().count());
      assertEquals(BigInteger.valueOf(3), rows.getFirst().idSum());
      assertEquals(0, Objects.requireNonNull(rows.getFirst().average()).compareTo(new BigDecimal("2")));
      assertEquals(3L, rows.getFirst().scalarCount());
      assertEquals(1, rows.get(1).count());
      assertNull(rows.get(1).average());
      // The same immutable query rebinds its captured suffix and keeps its detached generated reader.
      assertEquals(rows, query.fetchList());
      var reused = executor.select(AggregateSummaryPro.of(
              Sql.concat(table.category(), Sql.value("?")), Sql.countAll(),
              Sql.sumAsBigInteger(table.id()), Sql.avgAsBigDecimal(table.amount()), scalar))
          .from(table).groupBy(table.category()).orderBy(table.category().asc()).fetchList();
      assertEquals(List.of("A?", "B?"), reused.stream().map(row -> row.category()).toList());
    }
  }

  @Test
  void nullableAggregateScalarAndDerivedOutputsKeepTheirRowPresence() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      createRuntimeSamples(connection);
      SkisExecutor executor = executor(source, connection);
      var table = AggregateSampleTable.aggregateSampleTable;
      var sum = executor.select(Sql.sumAsLong(table.number())).from(table);
      assertTrue(sum.fetchOne().isPresent());
      assertEquals(3L, sum.fetchOne().orElseThrow());
      var empty = sum.where(table.id().lt(0L)).fetchOne();
      assertTrue(empty.hasRow());
      assertTrue(empty.isNull());
      assertFalse(sum.where(table.id().lt(0L)).having(Sql.countAll().gt(0L)).fetchOne().hasRow());
      assertEquals(0L, executor.select(Sql.countAll()).from(table).where(table.id().lt(0L)).fetchOne().orElseThrow());

      var inner = table.as("inner_count");
      var count = Sql.countAll();
      var output = Sql.output(count, "count");
      var derived = Sql.derived(Sql.select(count).from(inner), "derived_count", output);
      var outerCount = executor.selectNullable(derived.column(output)).from(table)
          .leftJoin(derived).on(table.id().lt(0L)).where(table.id().eq(1L)).fetchOne();
      assertTrue(outerCount.hasRow());
      assertTrue(outerCount.isNull());
      var scalar = Sql.scalar(Sql.select(inner.id()).from(inner).where(inner.id().lt(table.id())));
      assertTrue(executor.select(scalar).from(table).where(table.id().eq(1L)).fetchOne().isNull());
    }
  }

  @Test
  void standardResultsAndParameterRowsUseTheirDeclaredReaders() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      createRuntimeSamples(connection);
      SkisExecutor executor = executor(source, connection);
      var table = AggregateSampleTable.aggregateSampleTable;
      var key = Sql.add(Sql.coalesce(table.number(), Sql.value(0)), Sql.value(10));
      assertEquals(List.of(10, 11, 12), executor.select(key).from(table).groupBy(key)
          .orderBy(key.asc()).fetchList());
      var value = Sql.parameter(BigDecimal.class);
      assertEquals(0, executor.select(Sql.value(value)).from(table)
          .where(table.id().eq(1L)).bind(value, new BigDecimal("1.2500")).fetchOne().orElseThrow()
          .compareTo(new BigDecimal("1.2500")));
      assertEquals("A!", executor.select(Sql.caseWhen(table.number().gt(0),
              Sql.concat(table.category(), Sql.value("!")), Sql.value("none")))
          .from(table).where(table.id().eq(1L)).fetchOne().orElseThrow());
      assertEquals(2L, executor.select(Sql.cast(table.number(), Long.class, SqlType.BIGINT))
          .from(table).where(table.id().eq(2L)).fetchOne().orElseThrow());
    }
  }

  @Test
  void standardNullValuesCaseConcatAndCoalesceKeepTheirSqlNullContracts() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      createRuntimeSamples(connection);
      SkisExecutor executor = executor(source, connection);
      var table = AggregateSampleTable.aggregateSampleTable;
      assertTrue(executor.select(Sql.value(Integer.class, null)).from(table)
          .where(table.id().eq(1L)).fetchOne().isNull());
      assertTrue(executor.select(Sql.concat(table.category(), Sql.value(String.class, null))).from(table)
          .where(table.id().eq(1L)).fetchOne().isNull());
      assertTrue(executor.select(Sql.caseWhen(table.id().eq(1L),
              Sql.value(Long.class, null), Sql.value(2L))).from(table)
          .where(table.id().eq(1L)).fetchOne().isNull());
      assertEquals(9L, executor.select(Sql.coalesce(Sql.value(Long.class, null), Sql.value(9L)))
          .from(table).where(table.id().eq(1L)).fetchOne().orElseThrow());
      assertTrue(executor.select(Sql.cast(table.number(), Long.class, SqlType.BIGINT))
          .from(table).where(table.id().eq(3L)).fetchOne().isNull());
    }
  }

  @Test
  void nullableAndRequiredIntegerReadersRejectBothRangeEndsInTheLockedDriver() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      List<BigInteger> maxima = List.of(BigInteger.valueOf(Byte.MAX_VALUE), BigInteger.valueOf(Short.MAX_VALUE),
          BigInteger.valueOf(Integer.MAX_VALUE), BigInteger.valueOf(Long.MAX_VALUE));
      List<String> widerSqlTypes = List.of("SMALLINT", "INTEGER", "BIGINT", "NUMERIC");
      List<IntegerReader> required = List.of(
          rows -> JdbcCodecs.readByte(rows, 1, JdbcReadContext.EMPTY),
          rows -> JdbcCodecs.readShort(rows, 1, JdbcReadContext.EMPTY),
          rows -> JdbcCodecs.readInt(rows, 1, JdbcReadContext.EMPTY),
          rows -> JdbcCodecs.readLong(rows, 1, JdbcReadContext.EMPTY));
      List<IntegerReader> nullable = List.of(
          rows -> JdbcCodecs.readNullableByte(rows, 1, JdbcReadContext.EMPTY),
          rows -> JdbcCodecs.readNullableShort(rows, 1, JdbcReadContext.EMPTY),
          rows -> JdbcCodecs.readNullableInt(rows, 1, JdbcReadContext.EMPTY),
          rows -> JdbcCodecs.readNullableLong(rows, 1, JdbcReadContext.EMPTY));
      for (int index = 0; index < maxima.size(); index++) {
        BigInteger maximum = maxima.get(index);
        BigInteger minimum = maximum.negate().subtract(BigInteger.ONE);
        for (IntegerReader reader : List.of(required.get(index), nullable.get(index))) {
          for (String sqlType : java.util.stream.Stream.of("NUMERIC", widerSqlTypes.get(index))
              .distinct().toList()) {
            for (BigInteger value : List.of(minimum, maximum,
                minimum.subtract(BigInteger.ONE), maximum.add(BigInteger.ONE))) {
              try (var statement = connection.prepareStatement("SELECT CAST(? AS " + sqlType + ")")) {
                statement.setBigDecimal(1, new BigDecimal(value));
                try (ResultSet rows = statement.executeQuery()) {
                  assertTrue(rows.next());
                  if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
                    QueryValidationException failure =
                        assertThrows(QueryValidationException.class, () -> reader.read(rows));
                    assertEquals(SkisErrorCode.QUERY_RESULT_MAPPING, failure.code());
                    assertEquals("22003",
                        assertInstanceOf(SQLException.class, failure.getCause()).getSQLState());
                  } else {
                    assertEquals(value.toString(), Objects.requireNonNull(reader.read(rows)).toString());
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  @Test
  void generatedIntegerProjectionRejectsExpressionRangeOverflowAndKeepsNullRows() throws Exception {
    DataSource source = createDataSource();
    try (Connection connection = source.getConnection()) {
      // The wider physical column lets SQL produce values beyond the declared Integer domain.
      // The failure must come from the generated projection's reader, rather than SQL arithmetic.
      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TEMPORARY TABLE \"aggregate_samples\" (\"id\" BIGINT PRIMARY KEY, "
            + "\"category\" VARCHAR(20) NOT NULL, \"number\" BIGINT, \"amount\" NUMERIC(30, 10))");
        statement.executeUpdate("INSERT INTO \"aggregate_samples\" VALUES "
            + "(1, 'A', -2147483648, NULL), (2, 'A', 2147483647, NULL), "
            + "(3, 'A', -2147483649, NULL), (4, 'A', 2147483648, NULL), (5, 'A', NULL, NULL)");
      }
      SkisExecutor executor = executor(source, connection);
      var table = AggregateSampleTable.aggregateSampleTable;
      var query = executor.select(AggregateIntegerResultPro.of(Sql.add(table.number(), Sql.value(0))))
          .from(table);
      assertEquals(Integer.MIN_VALUE,
          query.where(table.id().eq(1L)).fetchOne().orElseThrow().value());
      assertEquals(Integer.MAX_VALUE,
          query.where(table.id().eq(2L)).fetchOne().orElseThrow().value());
      for (long id : List.of(3L, 4L)) {
        QueryValidationException failure = assertThrows(QueryValidationException.class,
            () -> query.where(table.id().eq(id)).fetchOne());
        assertEquals(SkisErrorCode.QUERY_RESULT_MAPPING, failure.code());
        assertEquals("22003", assertInstanceOf(SQLException.class, failure.getCause()).getSQLState());
        assertFalse(failure.diagnostics().safeText().contains("2147483649"));
        assertFalse(failure.diagnostics().safeText().contains("2147483648"));
        // A read failure must leave the borrowed connection usable by the same immutable query.
        assertEquals(Integer.MAX_VALUE,
            query.where(table.id().eq(2L)).fetchOne().orElseThrow().value());
      }
      var nullRow = query.where(table.id().eq(5L)).fetchOne();
      assertTrue(nullRow.hasRow());
      assertNull(nullRow.orElseThrow().value());
    }
  }

  @Test
  void exactIntegerAndDecimalReadersRejectFractionsAndPreserveNativeScale() throws Exception {
    try (Connection connection = createDataSource().getConnection();
        var statement = connection.prepareStatement("SELECT CAST(? AS NUMERIC(60, 10))")) {
      statement.setBigDecimal(1, new BigDecimal("123456789012345678901234567890.125"));
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        QueryValidationException failure = assertThrows(QueryValidationException.class,
            () -> JdbcCodecs.BIG_INTEGER.read(rows, 1, JdbcReadContext.EMPTY));
        assertEquals(SkisErrorCode.QUERY_RESULT_MAPPING, failure.code());
        assertInstanceOf(ArithmeticException.class, failure.getCause());
        assertEquals(rows.getBigDecimal(1), JdbcCodecs.BIG_DECIMAL.read(rows, 1, JdbcReadContext.EMPTY));
      }
      statement.setBigDecimal(1, new BigDecimal(LARGE_INTEGER));
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(LARGE_INTEGER, JdbcCodecs.BIG_INTEGER.read(rows, 1, JdbcReadContext.EMPTY));
      }
    }
  }

  @Test
  void approximateCodecsPreserveSupportedSpecialValuesWithoutReplacingThem() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
        try (var statement = connection.prepareStatement("SELECT CAST(? AS REAL), CAST(? AS DOUBLE PRECISION)")) {
          statement.setFloat(1, (float) value);
          statement.setDouble(2, value);
          try (ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            float real = Objects.requireNonNull(JdbcCodecs.FLOAT.read(rows, 1, JdbcReadContext.EMPTY));
            double approximate = Objects.requireNonNull(JdbcCodecs.DOUBLE.read(rows, 2, JdbcReadContext.EMPTY));
            if (Double.isNaN(value)) {
              assertTrue(Float.isNaN(real));
              assertTrue(Double.isNaN(approximate));
            } else {
              assertEquals((float) value, real);
              assertEquals(value, approximate);
            }
          }
        }
      }
    }
  }

  private SkisExecutor executor(DataSource source, Connection connection) {
    return Skis.builder(source).dialect(dialect()).connectionProvider(new ConnectionProvider() {
      @Override public Connection acquire(ExecutionContext context) { return connection; }
      @Override public void release(Connection borrowed, ExecutionContext context) {
        assertSame(connection, borrowed);
      }
    }).build();
  }

  private static void createRuntimeSamples(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("CREATE TEMPORARY TABLE \"aggregate_samples\" (\"id\" BIGINT PRIMARY KEY, "
          + "\"category\" VARCHAR(20) NOT NULL, \"number\" INTEGER, \"amount\" NUMERIC(30, 10))");
      statement.executeUpdate("INSERT INTO \"aggregate_samples\" VALUES (1, 'A', 1, 1.25), (2, 'A', 2, 2.75), (3, 'B', NULL, NULL)");
    }
  }

  @FunctionalInterface
  private interface IntegerReader {
    @Nullable Number read(ResultSet rows) throws SQLException;
  }

  @Test
  void distinctAggregateOrderingKeepsValuesAndNullRows() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable table = new NumberTable("n");
      var count = AggregateExpression.countAll();
      var sum = AggregateExpression.sumAsLong(table.integral);
      List<SqlExpression<?>> outputs = List.of(count, sum,
          AggregateExpression.sumAsDouble(table.approximate),
          new CastExpression<>(sum, BigDecimal.class),
          new ArithmeticExpression<>(count, ArithmeticOperator.ADD, LiteralExpression.one(Long.class)),
          new CoalesceExpression<>(List.of(sum, LiteralExpression.zero(Long.class))));
      List<OrderByItem> ordering = outputs.stream()
          .map(output -> new OrderByItem(output, OrderDirection.ASC, NullOrder.LAST)).toList();
      for (boolean empty : List.of(false, true)) {
        var query = new SelectStatement(true, outputs, List.of(), table, new BooleanPredicate(!empty),
            List.of(), null, ordering, null);
        try (PreparedStatement statement = prepare(connection, query); ResultSet rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(empty ? 0 : 3, rows.getLong(1));
          if (empty) {
            assertNull(rows.getObject(2));
            assertNull(rows.getObject(3));
            assertNull(rows.getObject(4));
          } else {
            assertEquals(2L * Integer.MAX_VALUE, rows.getLong(2));
            assertEquals(3.75, rows.getDouble(3));
            assertDecimal(BigDecimal.valueOf(2L * Integer.MAX_VALUE), rows, 4);
          }
          assertEquals(empty ? 1 : 4, rows.getLong(5));
          assertEquals(empty ? 0 : 2L * Integer.MAX_VALUE, rows.getLong(6));
          assertFalse(rows.next());
        }
      }
      var groupedSum = AggregateExpression.sumAsLong(table.category);
      var grouped = new SelectStatement(true, List.of(groupedSum), List.of(), table, null,
          List.of(table.category), null,
          List.of(new OrderByItem(groupedSum, OrderDirection.ASC, NullOrder.LAST)), null);
      try (PreparedStatement statement = prepare(connection, grouped); ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(2, rows.getLong(1));
        assertTrue(rows.next());
        assertNull(rows.getObject(1));
        assertFalse(rows.next());
      }
    }
  }

  @Test
  void booleanAndPrunedGroupKeysKeepEmptyInputAndNestedRowSemantics() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable table = new NumberTable("n");
      List<List<SqlExpression<?>>> keys = List.of(
          List.of(new BooleanPredicate(false)),
          List.of(new BooleanPredicate(true)),
          List.of(new InPredicate<>(table.id, List.of(), false)),
          List.of(new InPredicate<>(table.id, List.of(), true)),
          List.of(new InPredicate<>(table.id, List.of(), false),
              new InPredicate<>(table.longValue, List.of(), false),
              new CastExpression<>(new BooleanPredicate(false), Boolean.class)));
      for (List<SqlExpression<?>> groupBy : keys) {
        for (boolean empty : List.of(false, true)) {
          var query = new SelectStatement(false, List.of(AggregateExpression.countAll()), List.of(),
              table, new BooleanPredicate(!empty), groupBy, null, List.of(), null);
          try (PreparedStatement statement = prepare(connection, query); ResultSet rows = statement.executeQuery()) {
            assertEquals(!empty, rows.next());
            if (!empty) {
              assertEquals(3, rows.getLong(1));
              assertFalse(rows.next());
            }
          }
          var outer = new SelectStatement(List.of(AggregateExpression.countAll(),
              new ScalarSubqueryExpression<>(query)), new NumberTable("o"));
          try (PreparedStatement statement = prepare(connection, outer); ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(3, rows.getLong(1));
            assertEquals(empty ? 0 : 3, rows.getLong(2));
            assertEquals(empty, rows.wasNull());
            assertFalse(rows.next());
          }
        }
      }
    }
  }

  @Test
  void emptyMembershipKeepsOneGlobalAggregateRowEvenWhenANestedAggregateRemains() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable table = new NumberTable("n");
      NumberTable inner = new NumberTable("i");
      var nested = new ScalarSubqueryExpression<>(
          new SelectStatement(List.of(AggregateExpression.countAll()), inner));
      var unused = new ParameterSlot<>(0, Integer.class, false);
      List<SqlExpression<?>> aggregates = List.of(AggregateExpression.countAll(),
          AggregateExpression.countDistinct(table.integral), AggregateExpression.sumAsLong(unused),
          AggregateExpression.avgAsBigDecimal(unused), AggregateExpression.sumAsDouble(table.real),
          AggregateExpression.min(table.longValue), AggregateExpression.max(table.longValue));
      for (SqlExpression<?> aggregate : aggregates) {
        for (boolean negated : List.of(false, true)) {
          var empty = new InPredicate<>(aggregate, List.of(), negated);
          for (SqlPredicate where : List.of(new BooleanPredicate(true), FALSE)) {
            var query = new SelectStatement(List.of(empty, nested), table, where);
            assertTrue(SqlCompiler.compile(query, dialect()).parameterLayout().isEmpty());
            try (PreparedStatement statement = prepare(connection, query); ResultSet rows = statement.executeQuery()) {
              assertTrue(rows.next());
              assertEquals(negated, rows.getBoolean(1));
              assertEquals(3, rows.getLong(2));
              assertFalse(rows.next());
            }
          }
        }
      }
    }
  }

  @Test
  void uuidNullPredicatesDetermineTheParameterTypeBeforeExecution() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable table = new NumberTable("n");
      var parameter = new ParameterSlot<>(0, UUID.class, true);
      for (NullOperator operator : NullOperator.values()) {
        var query = new SelectStatement(List.of(AggregateExpression.countAll()), table,
            new NullPredicate(parameter, operator));
        for (@Nullable UUID value : Arrays.<@Nullable UUID>asList(
            null, UUID.fromString("67fb99eb-616f-4033-93ca-15cb61589e54"))) {
          try (PreparedStatement statement = prepare(connection, query, value); ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            boolean matches = (value == null) == (operator == NullOperator.IS_NULL);
            assertEquals(matches ? 3 : 0, rows.getLong(1));
            assertFalse(rows.next());
          }
        }
      }
    }
  }

  @Test
  void booleanUuidAndBinaryGroupingDistinguishStoredValues() throws Exception {
    assertGroupCounts(Boolean.class, "BOOLEAN", Arrays.asList(true, true, false, null), List.of(1L, 1L, 2L));
    UUID first = UUID.fromString("67fb99eb-616f-4033-93ca-15cb61589e54");
    UUID second = UUID.fromString("e482e297-d2bb-48d1-bd9e-ddd97eacb56b");
    assertGroupCounts(UUID.class, "UUID", Arrays.asList(first, UUID.fromString(first.toString()), second, null), List.of(1L, 1L, 2L));
    assertGroupCounts(byte[].class, dialect().id().equals("postgresql") ? "BYTEA" : "VARBINARY",
        Arrays.asList(new byte[] {1, 2}, new byte[] {1, 2}, new byte[] {2, 1}, null), List.of(1L, 1L, 2L));
  }

  @Test
  void nestedEmptyMembershipRetainsItsOwnAggregateRowOnEmptyInput() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable table = new NumberTable("n");
      NumberTable inner = new NumberTable("i");
      for (boolean negated : List.of(false, true)) {
        var empty = new InPredicate<>(AggregateExpression.countAll(), List.of(), negated);
        for (SqlPredicate where : List.of(new BooleanPredicate(true), FALSE)) {
          var scalar = new ScalarSubqueryExpression<>(new SelectStatement(List.of(empty), inner, where));
          var query = new SelectStatement(List.of(AggregateExpression.countAll(), scalar), table);
          try (PreparedStatement statement = prepare(connection, query); ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(3, rows.getLong(1));
            assertEquals(negated, rows.getBoolean(2));
            assertFalse(rows.wasNull());
            assertFalse(rows.next());
          }
        }
      }
    }
  }

  @Test
  void decimalGroupingUsesDatabaseEqualityAcrossScalesAndLargeValues() throws Exception {
    assertGroupCounts(BigDecimal.class, dialect().id().equals("h2") ? "DECFLOAT" : "NUMERIC",
        Arrays.asList(new BigDecimal("1.0"), new BigDecimal("1.00"), PRECISE, PRECISE.negate(), null),
        List.of(1L, 1L, 1L, 2L));
  }

  @Test
  void characterGroupingKeepsFixedAndVariableWidthPaddingRules() throws Exception {
    assertGroupCounts(String.class, "CHARACTER(4)", Arrays.asList("a", "a ", "b", null), List.of(1L, 1L, 2L));
    assertGroupCounts(String.class, "VARCHAR(4)", Arrays.asList("a", "a ", "b", null), List.of(1L, 1L, 1L, 1L));
    assertGroupCounts(Character.class, "CHARACTER(1)", Arrays.asList('a', 'a', 'b', null), List.of(1L, 1L, 2L));
  }

  @Test
  void timestampGroupingUsesInstantsRatherThanJavaOffsetEquality() throws Exception {
    OffsetDateTime first = OffsetDateTime.parse("2026-10-24T10:20:30.123456+08:00");
    assertGroupCounts(OffsetDateTime.class, "TIMESTAMP(6) WITH TIME ZONE",
        Arrays.asList(first, first.withOffsetSameInstant(java.time.ZoneOffset.UTC), first.plusSeconds(1), null),
        List.of(1L, 1L, 2L));
  }

  @Test
  void timeWithZoneGroupingKeepsEachDatabasesEqualityContract() throws Exception {
    OffsetTime first = OffsetTime.parse("10:20:30.123456+08:00");
    // PG distinguishes offsets after comparing UTC times; H2 compares only the UTC time.
    assertGroupCounts(OffsetTime.class, "TIME(6) WITH TIME ZONE",
        Arrays.asList(first, first.withOffsetSameInstant(java.time.ZoneOffset.UTC), first.plusSeconds(1), null),
        dialect().id().equals("postgresql") ? List.of(1L, 1L, 1L, 1L) : List.of(1L, 1L, 2L));
  }

  @Test
  void dateTimeAndIntegerGroupingDistinguishBoundariesAndNulls() throws Exception {
    LocalDate date = LocalDate.of(2026, 10, 24);
    LocalTime time = LocalTime.of(10, 20, 30, 123456000);
    LocalDateTime timestamp = LocalDateTime.of(date, time);
    assertGroupCounts(LocalDate.class, "DATE", Arrays.asList(date, date, date.plusDays(1), null), List.of(1L, 1L, 2L));
    assertGroupCounts(LocalTime.class, "TIME(6)", Arrays.asList(time, time, time.plusNanos(1000), null), List.of(1L, 1L, 2L));
    assertGroupCounts(LocalDateTime.class, "TIMESTAMP(6)",
        Arrays.asList(timestamp, timestamp, timestamp.plusNanos(1000), null), List.of(1L, 1L, 2L));
    assertGroupCounts(Long.class, "BIGINT", Arrays.asList(Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, null), List.of(1L, 1L, 2L));
    assertGroupCounts(BigInteger.class, "NUMERIC(60,0)",
        Arrays.asList(LARGE_INTEGER, LARGE_INTEGER, LARGE_INTEGER.negate(), null), List.of(1L, 1L, 2L));
  }

  @Test
  void approximateGroupingRetainsNativeNanAndInfinityEquality() throws Exception {
    assertGroupCounts(Double.class, "DOUBLE PRECISION",
        Arrays.asList(Double.NaN, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, null),
        List.of(1L, 1L, 1L, 2L));
    assertGroupCounts(Float.class, "REAL",
        Arrays.asList(Float.NaN, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null),
        List.of(1L, 1L, 1L, 2L));
  }

  private <V> void assertGroupCounts(Class<V> javaType, String sqlType,
      List<@Nullable V> values, List<Long> expected) throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      try (var sql = connection.createStatement()) {
        sql.execute("CREATE TEMPORARY TABLE \"aggregate_groups\" (\"id\" BIGINT NOT NULL, \"value\" " + sqlType + ")");
      }
      try (var insert = connection.prepareStatement("INSERT INTO \"aggregate_groups\" VALUES (?, ?)")) {
        for (int index = 0; index < values.size(); index++) {
          insert.setLong(1, index);
          bind(insert, 2, values.get(index));
          insert.executeUpdate();
        }
      }
      ValueTable<V> table = valueTable(javaType);
      var query = new SelectStatement(false, List.of(AggregateExpression.countAll()), List.of(),
          table, null, List.of(table.value), null, List.of(), null);
      try (PreparedStatement statement = prepare(connection, query); ResultSet rows = statement.executeQuery()) {
        List<Long> counts = new ArrayList<>();
        while (rows.next()) {
          counts.add(rows.getLong(1));
        }
        assertEquals(expected, counts.stream().sorted().toList(), javaType.getName() + "/" + sqlType);
      }
    }
  }

  @Test
  void aggregateValuesAndMetadataRetainTheDeclaredAccumulationDomains() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable table = new NumberTable("n");
      SelectStatement query = new SelectStatement(List.of(
          AggregateExpression.countAll(), AggregateExpression.count(table.integral), AggregateExpression.countDistinct(table.integral),
          AggregateExpression.sumAsLong(table.tiny), AggregateExpression.avgAsBigDecimal(table.tiny),
          AggregateExpression.sumAsLong(table.small), AggregateExpression.avgAsBigDecimal(table.small),
          AggregateExpression.sumAsLong(table.integral), AggregateExpression.avgAsBigDecimal(table.integral),
          AggregateExpression.sumAsBigInteger(table.longValue), AggregateExpression.avgAsBigDecimal(table.longValue),
          AggregateExpression.sumAsBigInteger(table.big), AggregateExpression.avgAsBigDecimal(table.big),
          AggregateExpression.sumAsBigDecimal(table.decimal), AggregateExpression.avgAsBigDecimal(table.decimal),
          AggregateExpression.sumAsDouble(table.real), AggregateExpression.avgAsDouble(table.real),
          AggregateExpression.sumAsDouble(table.approximate), AggregateExpression.avgAsDouble(table.approximate),
          AggregateExpression.min(table.integral), AggregateExpression.max(table.decimal)), table);
      try (PreparedStatement statement = prepare(connection, query); ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(3, rows.getLong(1));
        assertEquals(2, rows.getLong(2));
        assertEquals(1, rows.getLong(3));
        assertEquals(3, rows.getLong(4));
        assertDecimal(new BigDecimal("1.5"), rows, 5);
        assertEquals(65_534, rows.getLong(6));
        assertDecimal(BigDecimal.valueOf(Short.MAX_VALUE), rows, 7);
        assertEquals(2L * Integer.MAX_VALUE, rows.getLong(8));
        assertDecimal(BigDecimal.valueOf(Integer.MAX_VALUE), rows, 9);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), decimal(rows, 10).toBigIntegerExact());
        assertDecimal(BigDecimal.valueOf(Long.MAX_VALUE), rows, 11);
        assertEquals(LARGE_INTEGER.multiply(BigInteger.TWO), decimal(rows, 12).toBigIntegerExact());
        assertDecimal(new BigDecimal(LARGE_INTEGER), rows, 13);
        assertDecimal(PRECISE.add(new BigDecimal("0.000000000000000000000000000002")), rows, 14);
        assertDecimal(PRECISE.add(new BigDecimal("0.000000000000000000000000000002")).divide(BigDecimal.valueOf(2)), rows, 15);
        assertEquals(2.0 * Float.MAX_VALUE, rows.getDouble(16));
        assertTrue(Double.isFinite(rows.getDouble(16)));
        assertEquals((double) Float.MAX_VALUE, rows.getDouble(17));
        assertEquals(3.75, rows.getDouble(18));
        assertEquals(1.875, rows.getDouble(19));
        assertEquals(Integer.MAX_VALUE, rows.getInt(20));
        assertDecimal(PRECISE, rows, 21);
        assertEquals(Types.BIGINT, rows.getMetaData().getColumnType(1));
        assertTrue(List.of(Types.NUMERIC, Types.DECIMAL).contains(rows.getMetaData().getColumnType(5)));
        assertInstanceOf(BigDecimal.class, rows.getObject(5));
        assertInstanceOf(Double.class, rows.getObject(16));
        assertInstanceOf(Double.class, rows.getObject(18));
        if (dialect().id().equals("h2")) {
          assertEquals(10, rows.getMetaData().getScale(5));
          assertEquals(13, rows.getMetaData().getPrecision(5));
        }
        assertFalse(rows.next());
      }
    }
  }

  @Test
  void emptyInputAllNullAndHavingRetainSqlRowSemantics() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable table = new NumberTable("n");
      List<SqlExpression<?>> outputs = List.of(AggregateExpression.countAll(), AggregateExpression.count(table.integral),
          AggregateExpression.sumAsLong(table.integral), AggregateExpression.avgAsBigDecimal(table.integral),
          AggregateExpression.min(table.integral), AggregateExpression.max(table.integral));
      for (SqlPredicate filter : List.of(FALSE, table.id.eq(new ParameterSlot<>(0, Long.class, false)))) {
        try (PreparedStatement statement = prepare(connection, new SelectStatement(outputs, table, filter), 3L);
            ResultSet rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(filter == FALSE ? 0 : 1, rows.getLong(1));
          assertFalse(rows.wasNull());
          assertEquals(0, rows.getLong(2));
          assertFalse(rows.wasNull());
          for (int column = 3; column <= outputs.size(); column++) {
            assertNull(rows.getObject(column));
          }
          assertFalse(rows.next());
        }
      }
      var count = AggregateExpression.countAll();
      var rejected = new SelectStatement(false, List.of(count), List.of(), table, FALSE,
          List.of(), new ComparisonPredicate<>(count, ComparisonOperator.GREATER_THAN, LiteralExpression.zero(Long.class)), List.of(), null);
      try (PreparedStatement statement = prepare(connection, rejected); ResultSet rows = statement.executeQuery()) {
        assertFalse(rows.next());
      }
      for (SqlExpression<?> key : List.of(LiteralExpression.one(Integer.class), LiteralExpression.nullLiteral(String.class),
          new ParameterSlot<>(0, String.class, true))) {
        var grouped = new SelectStatement(false, List.of(count), List.of(), table, FALSE, List.of(key), null, List.of(), null);
        try (PreparedStatement statement = prepare(connection, grouped, (Object) null); ResultSet rows = statement.executeQuery()) {
          assertFalse(rows.next());
        }
      }
    }
  }

  @Test
  void outerJoinsPreserveDuplicateRowsAndCountNullExtendedInputs() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable left = new NumberTable("l");
      NumberTable right = new NumberTable("r");
      FromClause from = new FromClause(left, List.of(new JoinClause(JoinType.LEFT, right, left.category.eq(right.category))));
      SelectStatement query = new SelectStatement(List.of(AggregateExpression.countAll(), AggregateExpression.count(right.id),
          AggregateExpression.sumAsLong(left.tiny)), from);
      try (PreparedStatement statement = prepare(connection, query); ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(5, rows.getLong(1));
        assertEquals(4, rows.getLong(2));
        assertEquals(6, rows.getLong(3));
        assertFalse(rows.next());
      }
    }
  }

  @Test
  void parametersRemainOneDatabaseIdentityAcrossGroupingHavingAndOrdering() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable table = new NumberTable("n");
      ParameterSlot<Integer> step = new ParameterSlot<>(0, Integer.class, false);
      ParameterSlot<Long> minimum = new ParameterSlot<>(1, Long.class, false);
      var key = new ArithmeticExpression<>(table.category, ArithmeticOperator.ADD, step);
      var count = AggregateExpression.countAll();
      SelectStatement query = new SelectStatement(false, List.of(key, count), List.of(), table, null,
          List.of(new ArithmeticExpression<>(table.category, ArithmeticOperator.ADD, new ParameterSlot<>(0, Integer.class, false))),
          new ComparisonPredicate<>(count, ComparisonOperator.GREATER_THAN, minimum),
          List.of(new OrderByItem(key, OrderDirection.ASC, NullOrder.LAST)), null);
      SqlTemplate template = SqlCompiler.compile(query, dialect());
      assertEquals(List.of(0, 1), template.parameterLayout().slots().stream().map(ParameterSlot::ordinal).toList());
      try (PreparedStatement statement = prepare(connection, query, 100, 1L); ResultSet rows = statement.executeQuery()) {
        assertEquals(2, statement.getParameterMetaData().getParameterCount());
        assertEquals(Types.INTEGER, statement.getParameterMetaData().getParameterType(1));
        assertTrue(rows.next());
        assertEquals(101, rows.getInt(1));
        assertEquals(2, rows.getLong(2));
        assertFalse(rows.next());
      }
    }
  }

  @Test
  void booleanParameterizedGroupKeysKeepOneIdentityAndThreeValuedResults() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TEMPORARY TABLE \"aggregate_groups\" (\"id\" BIGINT NOT NULL, \"value\" VARCHAR(20))");
        statement.execute("INSERT INTO \"aggregate_groups\" VALUES (1, 'a'), (2, 'a'), (3, 'b'), (4, NULL)");
      }
      ValueTable<String> table = valueTable(String.class);
      var first = new ParameterSlot<>(0, String.class, false);
      var second = new ParameterSlot<>(1, String.class, false);
      List<SqlExpression<?>> keys = List.of(table.value.eq(first),
          new BetweenPredicate<>(table.value, first, second),
          new InPredicate<>(table.value, List.of(first), false),
          new LikePredicate(table.value, first));
      List<List<String>> arguments = List.of(List.of("a"), List.of("a", "az"), List.of("a"), List.of("a%"));
      for (int index = 0; index < keys.size(); index++) {
        SqlExpression<?> key = keys.get(index);
        List<String> values = arguments.get(index);
        for (boolean empty : List.of(false, true)) {
          var query = new SelectStatement(false, List.of(key, AggregateExpression.countAll()), List.of(),
              table, new BooleanPredicate(!empty), List.of(key), null, List.of(), null);
          assertEquals(values.size(), SqlCompiler.compile(query, dialect()).parameterCount());
          try (PreparedStatement statement = prepare(connection, query, values.toArray());
              ResultSet rows = statement.executeQuery()) {
            assertEquals(values.size(), statement.getParameterMetaData().getParameterCount());
            List<String> actual = new ArrayList<>();
            while (rows.next()) {
              actual.add(String.valueOf(rows.getObject(1)) + ":" + rows.getLong(2));
            }
            assertEquals(empty ? List.of() : List.of("false:1", "null:1", "true:2"),
                actual.stream().sorted().toList());
          }
        }
      }
    }
  }

  @Test
  void caseCoalesceCastAndDecimalGroupingDoNotLoseBoundValuePrecision() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable table = new NumberTable("n");
      ParameterSlot<Integer> threshold = new ParameterSlot<>(0, Integer.class, false);
      ParameterSlot<String> high = new ParameterSlot<>(1, String.class, false);
      ParameterSlot<String> low = new ParameterSlot<>(2, String.class, false);
      var classification = new CaseExpression<>(List.of(new CaseWhen<>(table.category.gt(threshold), high)), low);
      var query = grouped(table, List.of(classification, AggregateExpression.countAll()), List.of(classification));
      try (PreparedStatement statement = prepare(connection, query, 0, "large", "small"); ResultSet rows = statement.executeQuery()) {
        List<String> actual = new ArrayList<>();
        while (rows.next()) {
          actual.add(rows.getString(1) + ":" + rows.getLong(2));
        }
        assertEquals(List.of("large:2", "small:1"), actual.stream().sorted().toList());
      }
      ParameterSlot<Integer> fallback = new ParameterSlot<>(0, Integer.class, false);
      var coalesced = new CoalesceExpression<>(List.of(table.category, fallback));
      var cast = new CastExpression<>(coalesced, Long.class, SqlType.BIGINT);
      try (PreparedStatement statement = prepare(connection, grouped(table, List.of(cast, AggregateExpression.countAll()), List.of(cast)), 9);
          ResultSet rows = statement.executeQuery()) {
        List<Long> counts = new ArrayList<>();
        while (rows.next()) {
          counts.add(rows.getLong(2));
        }
        assertEquals(List.of(1L, 2L), counts.stream().sorted().toList());
      }
      ParameterSlot<BigDecimal> amount = new ParameterSlot<>(0, BigDecimal.class, false);
      var decimalKey = new CoalesceExpression<>(List.of(table.decimal, amount));
      BigDecimal preciseParameter = new BigDecimal("0.12345678901234567890123456789012345678901234567890");
      try (PreparedStatement statement = prepare(connection, grouped(table, List.of(decimalKey), List.of(decimalKey)), preciseParameter);
          ResultSet rows = statement.executeQuery()) {
        boolean found = false;
        while (rows.next()) {
          found |= decimal(rows, 1).compareTo(preciseParameter) == 0;
        }
        assertTrue(found, "the unbounded decimal parameter must not be cast to a fixed scale");
      }
    }
  }

  @Test
  void correlatedAggregateSubqueriesKeepTheOriginalScope() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable outer = new NumberTable("o");
      NumberTable inner = new NumberTable("i");
      ParameterSlot<Integer> step = new ParameterSlot<>(0, Integer.class, false);
      ParameterSlot<Long> minimum = new ParameterSlot<>(1, Long.class, false);
      var key = new ArithmeticExpression<>(outer.category, ArithmeticOperator.ADD, step);
      var scalar = new ScalarSubqueryExpression<>(new SelectStatement(List.of(AggregateExpression.countAll()), inner,
          LogicalPredicate.and(List.of(inner.category.eq(outer.category), inner.id.gt(minimum)))));
      try (PreparedStatement statement = prepare(connection,
          grouped(outer, List.of(key, scalar), List.of(key, outer.category)), 10, 0L); ResultSet rows = statement.executeQuery()) {
        List<Long> counts = new ArrayList<>();
        while (rows.next()) {
          counts.add(rows.getLong(2));
        }
        assertEquals(List.of(0L, 2L), counts.stream().sorted().toList());
      }
    }
  }

  @Test
  void everyGroupSqlTypeCountsStoredEqualValuesAndNulls() throws Exception {
    UUID uuid = UUID.fromString("96e8b7a1-7ed8-49b4-bc54-c7a154c515ec");
    List<GroupValue> values = List.of(
        new GroupValue(Byte.class, "SMALLINT", (byte) 3), new GroupValue(Short.class, "SMALLINT", (short) 3),
        new GroupValue(Integer.class, "INTEGER", 3), new GroupValue(Long.class, "BIGINT", 3L),
        new GroupValue(BigInteger.class, "NUMERIC(60,0)", LARGE_INTEGER), new GroupValue(BigDecimal.class, "NUMERIC(70,30)", PRECISE),
        new GroupValue(Float.class, "REAL", 1.25F), new GroupValue(Double.class, "DOUBLE PRECISION", 1.25),
        new GroupValue(Character.class, "CHARACTER(1)", 'a'), new GroupValue(String.class, "VARCHAR(30)", "same"),
        new GroupValue(Boolean.class, "BOOLEAN", true), new GroupValue(UUID.class, "UUID", uuid),
        new GroupValue(byte[].class, dialect().id().equals("postgresql") ? "BYTEA" : "VARBINARY", new byte[] {1, 2}),
        new GroupValue(LocalDate.class, "DATE", LocalDate.of(2026, 10, 24)),
        new GroupValue(LocalTime.class, "TIME(6)", LocalTime.of(10, 20, 30, 123456000)),
        new GroupValue(OffsetTime.class, "TIME(6) WITH TIME ZONE", OffsetTime.parse("10:20:30.123456+08:00")),
        new GroupValue(LocalDateTime.class, "TIMESTAMP(6)", LocalDateTime.of(2026, 10, 24, 10, 20, 30, 123456000)),
        new GroupValue(OffsetDateTime.class, "TIMESTAMP(6) WITH TIME ZONE", OffsetDateTime.parse("2026-10-24T10:20:30.123456+08:00")));
    for (GroupValue value : values) {
      try (Connection connection = createDataSource().getConnection()) {
        try (var sql = connection.createStatement()) {
          sql.execute("CREATE TEMPORARY TABLE \"aggregate_groups\" (\"id\" BIGINT NOT NULL, \"value\" " + value.sqlType() + ")");
        }
        try (var insert = connection.prepareStatement("INSERT INTO \"aggregate_groups\" VALUES (?, ?)")) {
          for (int index = 1; index <= 3; index++) {
            insert.setLong(1, index);
            Object bound = index == 3 ? null : value.value();
            if (bound instanceof byte[] bytes) {
              bound = bytes.clone();
            }
            bind(insert, 2, bound);
            insert.executeUpdate();
          }
        }
        ValueTable<?> table = valueTable(value.javaType());
        try (PreparedStatement statement = prepare(connection, new SelectStatement(false, List.of(AggregateExpression.countAll()), List.of(),
            table, null, List.of(table.value), null, List.of(), null)); ResultSet rows = statement.executeQuery()) {
          List<Long> counts = new ArrayList<>();
          while (rows.next()) {
            counts.add(rows.getLong(1));
          }
          assertEquals(List.of(1L, 2L), counts.stream().sorted().toList(), value.javaType().getName());
        }
        try (PreparedStatement statement = prepare(connection, new SelectStatement(
            List.of(AggregateExpression.count(table.value), AggregateExpression.countDistinct(table.value)), table));
            ResultSet rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(2, rows.getLong(1));
          assertEquals(1, rows.getLong(2));
        }
        if (value.javaType() != Boolean.class && value.javaType() != UUID.class && value.javaType() != byte[].class) {
          try (PreparedStatement statement = prepare(connection, new SelectStatement(
              List.of(AggregateExpression.min(table.value), AggregateExpression.max(table.value)), table));
              ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertNotNull(rows.getObject(1));
            assertEquals(rows.getObject(1), rows.getObject(2));
            if (value.value() instanceof Number number) {
              BigDecimal expected = number instanceof BigDecimal decimal ? decimal
                  : number instanceof BigInteger integer ? new BigDecimal(integer) : new BigDecimal(number.toString());
              assertDecimal(expected, rows, 1);
            } else if (value.javaType() == Character.class || value.javaType() == String.class) {
              assertEquals(value.value().toString(), rows.getString(1));
            } else if (value.value() instanceof OffsetDateTime expected) {
              assertEquals(expected.toInstant(), Objects.requireNonNull(rows.getObject(1, OffsetDateTime.class)).toInstant());
            } else if (value.value() instanceof OffsetTime expected) {
              assertTrue(expected.isEqual(Objects.requireNonNull(rows.getObject(1, OffsetTime.class))));
            } else {
              assertEquals(value.value(), rows.getObject(1, value.javaType()));
            }
          }
        }
      }
    }
  }

  @Test
  void integerDivisionOverflowAndZeroDivisionFailWithoutSilentJavaArithmetic() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable table = new NumberTable("n");
      var left = new ParameterSlot<>(0, Integer.class, false);
      var right = new ParameterSlot<>(1, Integer.class, false);
      var divide = new ArithmeticExpression<>(left, ArithmeticOperator.DIVIDE, right);
      SelectStatement query = new SelectStatement(List.of(divide), table, table.id.eq(LiteralExpression.one(Long.class)));
      for (int[] pair : List.of(new int[] {5, 2}, new int[] {-5, 2}, new int[] {5, -2}, new int[] {-5, -2})) {
        try (PreparedStatement statement = prepare(connection, query, pair[0], pair[1]); ResultSet rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(pair[0] / pair[1], rows.getInt(1));
        }
      }
      assertExecutionFails(connection, query, 1, 0);
      assertExecutionFails(connection, query, Integer.MIN_VALUE, -1);
      var longLeft = new ParameterSlot<>(0, Long.class, false);
      var longRight = new ParameterSlot<>(1, Long.class, false);
      assertExecutionFails(connection, new SelectStatement(List.of(new ArithmeticExpression<>(longLeft, ArithmeticOperator.DIVIDE, longRight)), table),
          Long.MIN_VALUE, -1L);
      assertExecutionFails(connection, new SelectStatement(List.of(new ArithmeticExpression<>(longLeft, ArithmeticOperator.ADD, longRight)), table),
          Long.MAX_VALUE, 1L);
      var add = new ArithmeticExpression<>(left, ArithmeticOperator.ADD, right);
      assertExecutionFails(connection, new SelectStatement(List.of(add), table), Integer.MAX_VALUE, 1);
      var numerator = new ParameterSlot<>(0, BigDecimal.class, false);
      var denominator = new ParameterSlot<>(1, BigDecimal.class, false);
      assertExecutionFails(connection, new SelectStatement(List.of(new ArithmeticExpression<>(numerator, ArithmeticOperator.DIVIDE, denominator)), table),
          BigDecimal.ONE, BigDecimal.ZERO);
    }
  }

  @Test
  void approximateSpecialValuesRetainDatabaseAndReaderBehavior() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable table = new NumberTable("n");
      for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
        var parameter = new ParameterSlot<>(0, Double.class, false);
        for (boolean average : List.of(false, true)) {
          var aggregate = average ? AggregateExpression.avgAsDouble(parameter)
              : AggregateExpression.sumAsDouble(parameter);
          SelectStatement query = new SelectStatement(List.of(aggregate), table,
              table.id.eq(LiteralExpression.one(Long.class)));
          try (PreparedStatement statement = prepare(connection, query, value)) {
            // H2 accumulates AVG(DOUBLE) through BigDecimal, which cannot represent NaN or Infinity.
            if (average && dialect().id().equals("h2")) {
              SQLException failure = assertThrows(SQLException.class, () -> {
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) { rows.getDouble(1); } }
              });
              assertEquals("22018", failure.getSQLState());
              continue;
            }
            try (ResultSet rows = statement.executeQuery()) {
              assertTrue(rows.next());
              if (Double.isNaN(value)) {
                assertTrue(Double.isNaN(rows.getDouble(1)));
              } else {
                assertEquals(value, rows.getDouble(1));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void approximateOverflowAndZeroDivisionHaveExplicitDialectOutcomes() throws Exception {
    try (Connection connection = createDataSource().getConnection()) {
      createNumbers(connection);
      NumberTable table = new NumberTable("n");
      var value = new ParameterSlot<>(0, Double.class, false);
      var maximumId = new ParameterSlot<>(1, Long.class, false);
      var overflowing = new SelectStatement(List.of(AggregateExpression.sumAsDouble(value)), table, table.id.lt(maximumId));
      try (PreparedStatement statement = prepare(connection, overflowing, Double.MAX_VALUE, 3L)) {
        if (dialect().id().equals("postgresql")) {
          SQLException failure = assertThrows(SQLException.class, () -> {
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) { rows.getDouble(1); } }
          });
          assertEquals("22003", failure.getSQLState());
        } else {
          try (ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(Double.POSITIVE_INFINITY, rows.getDouble(1));
          }
        }
      }
      var denominator = new ParameterSlot<>(1, Double.class, false);
      var divided = new SelectStatement(List.of(new ArithmeticExpression<>(value, ArithmeticOperator.DIVIDE, denominator)), table);
      try (PreparedStatement statement = prepare(connection, divided, 1.0, 0.0)) {
        SQLException failure = assertThrows(SQLException.class, () -> {
          try (ResultSet rows = statement.executeQuery()) { while (rows.next()) { rows.getDouble(1); } }
        });
        assertEquals("22012", failure.getSQLState());
      }
    }
  }

  private PreparedStatement prepare(Connection connection, SelectStatement query, @Nullable Object... arguments) throws SQLException {
    SqlTemplate template = SqlCompiler.compile(query, dialect());
    PreparedStatement statement = connection.prepareStatement(template.sql());
    try {
      for (int index = 0; index < template.parameterCount(); index++) {
        var parameter = template.parameterLayout().slots().get(index);
        Object argument = arguments[parameter.ordinal()];
        if (argument == null) {
          statement.setNull(index + 1, jdbcType(parameter.sqlType()));
        } else {
          bind(statement, index + 1, argument);
        }
      }
      return statement;
    } catch (SQLException | RuntimeException failure) {
      try { statement.close(); } catch (SQLException close) { failure.addSuppressed(close); }
      throw failure;
    }
  }

  private void assertExecutionFails(Connection connection, SelectStatement query, @Nullable Object... arguments) throws Exception {
    try (PreparedStatement statement = prepare(connection, query, arguments)) {
      assertThrows(SQLException.class, () -> {
        try (ResultSet rows = statement.executeQuery()) { while (rows.next()) { rows.getObject(1); } }
      });
    }
  }

  private static void bind(PreparedStatement statement, int index, @Nullable Object value) throws SQLException {
    if (value instanceof BigInteger integer) {
      statement.setBigDecimal(index, new BigDecimal(integer));
    } else if (value instanceof Character character) {
      statement.setString(index, character.toString());
    } else if (value instanceof byte[] bytes) {
      statement.setBytes(index, bytes);
    } else {
      statement.setObject(index, value);
    }
  }

  private static int jdbcType(SqlType type) {
    return switch (type) {
      case BOOLEAN -> Types.BOOLEAN;
      case TINYINT -> Types.TINYINT;
      case SMALLINT -> Types.SMALLINT;
      case INTEGER -> Types.INTEGER;
      case BIGINT -> Types.BIGINT;
      case REAL -> Types.REAL;
      case DOUBLE -> Types.DOUBLE;
      case DECIMAL -> Types.DECIMAL;
      case CHARACTER -> Types.CHAR;
      case VARCHAR -> Types.VARCHAR;
      case VARBINARY -> Types.VARBINARY;
      case UUID, OTHER -> Types.OTHER;
      case DATE -> Types.DATE;
      case TIME -> Types.TIME;
      case TIME_WITH_TIME_ZONE -> Types.TIME_WITH_TIMEZONE;
      case TIMESTAMP -> Types.TIMESTAMP;
      case TIMESTAMP_WITH_TIME_ZONE -> Types.TIMESTAMP_WITH_TIMEZONE;
    };
  }

  private static void assertDecimal(BigDecimal expected, ResultSet rows, int column) throws SQLException {
    assertEquals(0, decimal(rows, column).compareTo(expected), "decimal column #" + column);
  }

  private static BigDecimal decimal(ResultSet rows, int column) throws SQLException {
    return Objects.requireNonNull(rows.getBigDecimal(column), "non-NULL decimal column #" + column);
  }

  private static SelectStatement grouped(NumberTable table, List<SqlExpression<?>> outputs, List<SqlExpression<?>> keys) {
    return new SelectStatement(false, outputs, List.of(), table, null, keys, null, List.of(), null);
  }

  private static void createNumbers(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("CREATE TEMPORARY TABLE \"aggregate_numbers\" (\"id\" BIGINT NOT NULL, \"category\" INTEGER, \"tiny_value\" SMALLINT, "
          + "\"small_value\" SMALLINT, \"int_value\" INTEGER, \"long_value\" BIGINT, \"big_value\" NUMERIC(60,0), \"decimal_value\" NUMERIC(70,30), "
          + "\"real_value\" REAL, \"double_value\" DOUBLE PRECISION)");
    }
    try (var insert = connection.prepareStatement("INSERT INTO \"aggregate_numbers\" VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      for (int row = 1; row <= 3; row++) {
        insert.setLong(1, row);
        if (row == 3) {
          for (int index = 2; index <= 10; index++) { insert.setObject(index, null); }
        } else {
          insert.setInt(2, 1);
          insert.setByte(3, (byte) row);
          insert.setShort(4, Short.MAX_VALUE);
          insert.setInt(5, Integer.MAX_VALUE);
          insert.setLong(6, Long.MAX_VALUE);
          insert.setBigDecimal(7, new BigDecimal(LARGE_INTEGER));
          insert.setBigDecimal(8, row == 1 ? PRECISE : new BigDecimal("0.000000000000000000000000000002"));
          insert.setFloat(9, Float.MAX_VALUE);
          insert.setDouble(10, row == 1 ? 1.25 : 2.5);
        }
        insert.executeUpdate();
      }
    }
  }

  private record GroupValue(Class<?> javaType, String sqlType, Object value) {}
  private record Row() {}

  private static <V> ValueTable<V> valueTable(Class<V> type) {
    PropertyMeta<Row, Long> id = new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
    PropertyMeta<Row, V> value = new PropertyMeta<>(1, "value", type, ColumnMeta.of("value", true));
    EntityMeta<Row> entity = EntityMeta.simple(Row.class, TableMeta.of("aggregate_groups"),
        List.of(id, value), new PrimaryKeyMeta<>(List.of(id)), false);
    return new ValueTable<>(entity, value);
  }

  private static final class ValueTable<V> extends TableExpression<Row> {
    private final ColumnExpression<Row, V> value;
    private ValueTable(EntityMeta<Row> entity, PropertyMeta<Row, V> property) {
      super(entity);
      value = column(property);
    }
    @Override public ValueTable<V> as(Identifier alias) { throw new UnsupportedOperationException("fixture does not alias"); }
  }

  private static final class NumberTable extends TableExpression<Row> {
    private static final PropertyMeta<Row, Long> ID = property(0, "id", Long.class);
    private static final PropertyMeta<Row, Integer> CATEGORY = property(1, "category", Integer.class);
    private static final PropertyMeta<Row, Byte> TINY = property(2, "tiny_value", Byte.class);
    private static final PropertyMeta<Row, Short> SMALL = property(3, "small_value", Short.class);
    private static final PropertyMeta<Row, Integer> INTEGRAL = property(4, "int_value", Integer.class);
    private static final PropertyMeta<Row, Long> LONG = property(5, "long_value", Long.class);
    private static final PropertyMeta<Row, BigInteger> BIG = property(6, "big_value", BigInteger.class);
    private static final PropertyMeta<Row, BigDecimal> DECIMAL = property(7, "decimal_value", BigDecimal.class);
    private static final PropertyMeta<Row, Float> REAL = property(8, "real_value", Float.class);
    private static final PropertyMeta<Row, Double> APPROXIMATE = property(9, "double_value", Double.class);
    private static final EntityMeta<Row> ENTITY = EntityMeta.simple(Row.class, TableMeta.of("aggregate_numbers"),
        List.of(ID, CATEGORY, TINY, SMALL, INTEGRAL, LONG, BIG, DECIMAL, REAL, APPROXIMATE), new PrimaryKeyMeta<>(List.of(ID)), false);
    private final ColumnExpression<Row, Long> id = column(ID);
    private final ColumnExpression<Row, Integer> category = column(CATEGORY);
    private final ColumnExpression<Row, Byte> tiny = column(TINY);
    private final ColumnExpression<Row, Short> small = column(SMALL);
    private final ColumnExpression<Row, Integer> integral = column(INTEGRAL);
    private final ColumnExpression<Row, Long> longValue = column(LONG);
    private final ColumnExpression<Row, BigInteger> big = column(BIG);
    private final ColumnExpression<Row, BigDecimal> decimal = column(DECIMAL);
    private final ColumnExpression<Row, Float> real = column(REAL);
    private final ColumnExpression<Row, Double> approximate = column(APPROXIMATE);
    private NumberTable(String alias) { super(ENTITY, Identifier.of(alias)); }
    @Override public NumberTable as(Identifier alias) { return new NumberTable(alias.value()); }
    private static <V> PropertyMeta<Row, V> property(int ordinal, String name, Class<V> type) {
      return new PropertyMeta<>(ordinal, name, type, ColumnMeta.of(name, ordinal != 0));
    }
  }
}
