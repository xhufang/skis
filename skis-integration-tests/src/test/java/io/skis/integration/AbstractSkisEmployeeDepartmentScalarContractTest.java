package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.skis.core.spi.ExecutionContext;
import io.skis.dialect.spi.Dialect;
import io.skis.entity.skis.DepartmentTable;
import io.skis.entity.skis.EmployeeTable;
import io.skis.jdbc.spi.ConnectionProvider;
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
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Parameterized DISTINCT scalar ordering using the employee/department test model. */
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

}
