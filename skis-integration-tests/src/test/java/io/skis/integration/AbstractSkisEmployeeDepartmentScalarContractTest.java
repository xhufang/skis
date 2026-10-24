package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.spi.ExecutionContext;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.support.SqlCompiler;
import io.skis.entity.skis.DepartmentTable;
import io.skis.entity.skis.EmployeeTable;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.metadata.ColumnMeta;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.metadata.TableMeta;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.query.QueryParameter;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnSelect;
import io.skis.query.Slice;
import io.skis.query.SliceRequest;
import io.skis.query.Sql;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import io.skis.sql.ast.ArithmeticExpression;
import io.skis.sql.ast.ArithmeticOperator;
import io.skis.sql.ast.CastExpression;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.NullOrder;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.OrderDirection;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.ScalarSubqueryExpression;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.TableExpression;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Parameterized DISTINCT scalar and computed ordering using employee/department fixtures. */
abstract class AbstractSkisEmployeeDepartmentScalarContractTest {

  protected abstract DataSource createDataSource();

  protected abstract Dialect dialect();

  @Test
  void ordersEmployeeDepartmentScalarResults() throws Exception {
    verifyDistinctScalarOrdering(createDataSource(), dialect());
  }

  private static void verifyDistinctScalarOrdering(DataSource dataSource, Dialect dialect)
      throws Exception {
    // The test owns this connection and its temporary tables. Only columns used by these
    // scalar queries are needed; no complete employee or department decoder is invoked.
    try (Connection connection = dataSource.getConnection()) {
      try (Statement statement = connection.createStatement()) {
        statement.execute(
            "CREATE TEMPORARY TABLE \"CL_DEPARTMENT\" "
                + "(\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(100) NOT NULL)");
        statement.execute(
            "CREATE TEMPORARY TABLE \"CL_EMPLOYEE\" "
                + "(\"id\" BIGINT PRIMARY KEY, \"department_id\" BIGINT NOT NULL)");
        statement.execute(
            "INSERT INTO \"CL_DEPARTMENT\" (\"id\", \"name\") "
                + "VALUES (10, '研发部'), (20, '财务部'), (30, 'Warehouse')");
        statement.execute(
            "INSERT INTO \"CL_EMPLOYEE\" (\"id\", \"department_id\") "
                + "VALUES (1, 10), (2, 10), (3, 20), (4, 30)");
      }
      verifyDistinctComputedOrdering(connection, dialect);
      SkisExecutor executor =
          Skis.builder(dataSource)
              .connectionProvider(
                  new ConnectionProvider() {
                    @Override
                    public Connection acquire(ExecutionContext context) {
                      return connection;
                    }

                    @Override
                    public void release(Connection released, ExecutionContext context) {
                      // The surrounding test retains ownership across content/count statements.
                    }
                  })
              .dialect(dialect)
              .build();
      EmployeeTable employee = EmployeeTable.employeeTable.as("e");
      DepartmentTable department = DepartmentTable.departmentTable.as("d");
      QueryParameter<String> pattern = Sql.parameter(String.class, "departmentPattern");
      QueryParameter<Long> minimumEmployeeId = Sql.parameter(Long.class, "minimumEmployeeId");
      Selectable<Long> departmentId =
          Sql.scalar(
              Sql.select(department.id())
                  .from(department)
                  .where(
                      department
                          .id()
                          .eq(employee.departmentId())
                          .and(department.name().like(pattern))));
      SingleColumnSelect<@Nullable Long> description =
          Sql.select(departmentId)
              .from(employee)
              .where(employee.id().ge(minimumEmployeeId))
              .distinct()
              .orderBy(departmentId.asc().nullsLast());
      var query = executor.query(description).bind(pattern, "%部").bind(minimumEmployeeId, 1L);

      // Two employees share department 10; the unmatched department 30 becomes one SQL NULL.
      assertEquals(Arrays.asList(10L, 20L, null), query.fetchList());
      assertEquals(Arrays.asList(10L, 20L, null), query.fetchList());
      try (var rows = query.stream()) {
        assertEquals(Arrays.asList(10L, 20L, null), rows.toList());
      }
      assertEquals(
          Arrays.asList(null, 20L, 10L),
          query.orderBy(departmentId.desc().nullsFirst()).fetchList());
      Page<@Nullable Long> firstPage = query.fetchPage(PageRequest.page(0, 2));
      assertEquals(List.of(10L, 20L), firstPage.items());
      assertEquals(3L, firstPage.totalElements());
      Page<@Nullable Long> lastPage = query.fetchPage(PageRequest.page(1, 2));
      assertEquals(Collections.singletonList(null), lastPage.items());
      assertEquals(3L, lastPage.totalElements());

      Slice<@Nullable Long> nullableSlice =
          executor
              .select(departmentId)
              .from(employee)
              .where(employee.id().ge(minimumEmployeeId))
              .orderBy(employee.id().asc())
              .bind(pattern, "%部")
              .bind(minimumEmployeeId, 1L)
              .fetchSlice(SliceRequest.offset(0, 4));
      assertEquals(Arrays.asList(10L, 10L, 20L, null), nullableSlice.items());
      assertFalse(nullableSlice.hasNext());

      // The same description also has to be laid out correctly when embedded in another block.
      DepartmentTable outerDepartment = DepartmentTable.departmentTable.as("outer_d");
      var nested =
          Sql.select(outerDepartment.id())
              .from(outerDepartment)
              .where(outerDepartment.id().in(description))
              .orderBy(outerDepartment.id().asc());
      assertEquals(
          List.of(10L, 20L),
          executor.query(nested).bind(pattern, "%部").bind(minimumEmployeeId, 1L).fetchList());

      assertEquals(
          Arrays.asList(10L, null),
          executor.query(description).bind(pattern, "研发%").bind(minimumEmployeeId, 1L).fetchList());
    }
  }

  private static void verifyDistinctComputedOrdering(Connection connection, Dialect dialect)
      throws Exception {
    IdTable outer = new IdTable("computed_e");
    IdTable inner = new IdTable("computed_inner_e");
    ParameterSlot<Long> offset = new ParameterSlot<>(0, Long.class, false);
    assertDistinctComputedRows(connection, dialect, outer,
        new ArithmeticExpression<>(outer.id, ArithmeticOperator.ADD, offset),
        List.of(6L, 7L, 8L, 9L), 5L);

    ParameterSlot<Long> minimum = new ParameterSlot<>(0, Long.class, false);
    ParameterSlot<Long> nestedOffset = new ParameterSlot<>(1, Long.class, false);
    ScalarSubqueryExpression<Long> scalar = new ScalarSubqueryExpression<>(new SelectStatement(
        List.of(inner.id), inner,
        LogicalPredicate.and(List.of(inner.id.eq(outer.id), inner.id.ge(minimum)))));
    SqlExpression<Long> expression = new ArithmeticExpression<>(
        new CastExpression<>(scalar, Long.class, SqlType.BIGINT),
        ArithmeticOperator.ADD, nestedOffset);
    assertDistinctComputedRows(connection, dialect, outer, expression,
        Arrays.asList(8L, 9L, null), 3L, 5L);
  }

  /** Exercises normalization and JDBC without depending on T06's expression result mapping. */
  private static void assertDistinctComputedRows(Connection connection, Dialect dialect,
      IdTable table, SqlExpression<Long> expression, List<@Nullable Long> expected, long... values)
      throws Exception {
    var template = SqlCompiler.compile(new SelectStatement(true, List.of(expression), List.of(),
        table, null,
        List.of(new OrderByItem(expression, OrderDirection.ASC, NullOrder.LAST)), null), dialect);
    assertTrue(template.sql().endsWith("ORDER BY 1 ASC NULLS LAST"));
    assertEquals(IntStream.range(0, values.length).boxed().toList(),
        template.parameterLayout().slots().stream().map(ParameterSlot::ordinal).toList());
    try (var statement = connection.prepareStatement(template.sql())) {
      for (int index = 0; index < values.length; index++) {
        statement.setLong(index + 1, values[index]);
      }
      try (var rows = statement.executeQuery()) {
        List<@Nullable Long> actual = new ArrayList<>();
        while (rows.next()) {
          long value = rows.getLong(1);
          actual.add(rows.wasNull() ? null : value);
        }
        assertEquals(expected, actual);
      }
    }
  }

  private record EmployeeId(Long id) {}

  private static final class IdTable extends TableExpression<EmployeeId> {
    private static final PropertyMeta<EmployeeId, Long> ID =
        new PropertyMeta<>(0, "id", Long.class, ColumnMeta.of("id", false));
    private static final EntityMeta<EmployeeId> ENTITY = EntityMeta.simple(EmployeeId.class,
        new TableMeta("", "", "CL_EMPLOYEE"), List.of(ID), new PrimaryKeyMeta<>(List.of(ID)), false);
    private final ColumnExpression<EmployeeId, Long> id = column(ID);

    private IdTable(String alias) {
      super(ENTITY, Identifier.of(alias));
    }

    @Override
    public IdTable as(Identifier alias) {
      return new IdTable(alias.value());
    }
  }
}
