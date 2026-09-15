package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.skis.core.ExecutionContext;
import io.skis.dialect.Dialect;
import io.skis.dialect.h2.H2Dialect;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.entity.skis.DepartmentTable;
import io.skis.entity.skis.EmployeeTable;
import io.skis.jdbc.ConnectionProvider;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.query.QueryParameter;
import io.skis.query.QueryParameters;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnSelect;
import io.skis.query.Sql;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Parameterized DISTINCT scalar ordering using the employee/department test model. */
class SkisEmployeeDepartmentScalarContractTest {

  @Test
  void ordersEmployeeDepartmentScalarResultsAgainstH2() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:employee_scalar_" + UUID.randomUUID());
    verifyDistinctScalarOrdering(dataSource, H2Dialect.INSTANCE);
  }

  @Test
  void ordersEmployeeDepartmentScalarResultsAgainstPostgreSql() throws Exception {
    String url = System.getenv("SKIS_POSTGRES_URL");
    assumeTrue(url != null && !url.isBlank(), "SKIS_POSTGRES_URL is not configured");
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(url);
    dataSource.setUser(environmentOrDefault("SKIS_POSTGRES_USER", "skis"));
    dataSource.setPassword(environmentOrDefault("SKIS_POSTGRES_PASSWORD", "skis"));
    verifyDistinctScalarOrdering(dataSource, PostgreSqlDialect.INSTANCE);
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
          SkisExecutorFactory.builder()
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
      EmployeeTable employee = EmployeeTable.EMPLOYEE.as("e");
      DepartmentTable department = DepartmentTable.DEPARTMENT.as("d");
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
      SingleColumnSelect<Long> description =
          Sql.select(departmentId)
              .from(employee)
              .where(employee.id().ge(minimumEmployeeId))
              .distinct()
              .orderBy(departmentId.asc().nullsLast());
      QueryParameters parameters =
          QueryParameters.builder().bind(pattern, "%部").bind(minimumEmployeeId, 1L).build();
      var query = executor.query(description, parameters);

      // Two employees share department 10; the unmatched department 30 becomes one SQL NULL.
      assertEquals(Arrays.asList(10L, 20L, null), query.fetchList());
      assertEquals(Arrays.asList(10L, 20L, null), query.fetchList());
      assertEquals(
          Arrays.asList(null, 20L, 10L),
          query.orderBy(departmentId.desc().nullsFirst()).fetchList());
      Page<Long> firstPage = query.fetchPage(PageRequest.page(0, 2));
      assertEquals(List.of(10L, 20L), firstPage.items());
      assertEquals(3L, firstPage.totalElements());
      Page<Long> lastPage = query.fetchPage(PageRequest.page(1, 2));
      assertEquals(Collections.singletonList(null), lastPage.items());
      assertEquals(3L, lastPage.totalElements());

      // The same description also has to be laid out correctly when embedded in another block.
      DepartmentTable outerDepartment = DepartmentTable.DEPARTMENT.as("outer_d");
      var nested =
          Sql.select(outerDepartment.id())
              .from(outerDepartment)
              .where(outerDepartment.id().in(description))
              .orderBy(outerDepartment.id().asc());
      assertEquals(List.of(10L, 20L), executor.query(nested, parameters).fetchList());

      QueryParameters otherParameters =
          QueryParameters.builder().bind(pattern, "研发%").bind(minimumEmployeeId, 1L).build();
      assertEquals(Arrays.asList(10L, null), executor.query(description, otherParameters).fetchList());
    }
  }

  private static String environmentOrDefault(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
