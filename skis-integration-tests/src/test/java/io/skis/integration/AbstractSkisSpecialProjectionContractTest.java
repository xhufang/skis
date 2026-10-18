package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.Dialect;
import io.skis.entity.skis.EmployeeTable;
import io.skis.entity.skis.ProjectTable;
import io.skis.projection.ProjectPrincipalView;
import io.skis.projection.skis.ProjectPrincipalViewPro;
import io.skis.query.QueryPlanCacheStatistics;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared real-driver contract for generated special projections and their immutable singleton. */
abstract class AbstractSkisSpecialProjectionContractTest {

  private static final ProjectTable PROJECT = ProjectTable.projectTable;
  private static final EmployeeTable EMPLOYEE = EmployeeTable.employeeTable;

  private DataSource dataSource;
  private SkisExecutor executor;
  private long employeeId;
  private long projectWithPrincipalId;
  private long projectWithoutPrincipalId;

  protected abstract DataSource createDataSource() throws Exception;

  protected abstract Dialect dialect();

  @BeforeEach
  void setUpSpecialProjectionContract() throws Exception {
    dataSource = createDataSource();
    prepareSchema(dataSource);
    executor = Skis.create(dataSource, dialect());

    long base = 10_000L + (UUID.randomUUID().getMostSignificantBits() & 0x1ffffffffffff000L);
    employeeId = base;
    projectWithPrincipalId = base + 1;
    projectWithoutPrincipalId = base + 2;
    insertRows();
  }

  @AfterEach
  void cleanSpecialProjectionRows() throws Exception {
    if (dataSource == null || employeeId == 0) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement projects =
            connection.prepareStatement("DELETE FROM \"CL_PROJECT\" WHERE \"id\" IN (?, ?)");
        PreparedStatement employees =
            connection.prepareStatement("DELETE FROM \"CL_EMPLOYEE\" WHERE \"id\" = ?")) {
      projects.setLong(1, projectWithPrincipalId);
      projects.setLong(2, projectWithoutPrincipalId);
      projects.executeUpdate();
      employees.setLong(1, employeeId);
      employees.executeUpdate();
    }
  }

  @Test
  void executesGeneratedSpecialProjectionForPresentAndMissingLeftJoinRows() {
    List<ProjectPrincipalView> rows =
        executor
            .select(ProjectPrincipalViewPro.all())
            .from(PROJECT)
            .leftJoin(EMPLOYEE)
            .on(PROJECT.principalId().eq(EMPLOYEE.id()))
            .where(PROJECT.id().in(List.of(projectWithPrincipalId, projectWithoutPrincipalId)))
            .orderBy(PROJECT.id().asc())
            .fetchList();

    assertEquals(
        List.of(
            new ProjectPrincipalView(
                projectWithPrincipalId, "Project with principal", employeeId, "Ada"),
            new ProjectPrincipalView(
                projectWithoutPrincipalId, "Project without principal", null, null)),
        rows);
  }

  @Test
  void reusesOneSpecialProjectionAcrossQueriesExecutorsThreadsAndPlanCaches() throws Exception {
    ProjectPrincipalViewPro projection = ProjectPrincipalViewPro.all();
    assertSame(projection, ProjectPrincipalViewPro.all());
    SkisExecutor secondExecutor = Skis.create(dataSource, dialect());

    QueryPlanCacheStatistics firstBefore = executor.queryPlanCacheStatistics();
    assertEquals(
        expectedWithPrincipal(), findPrincipal(executor, projection, projectWithPrincipalId));
    QueryPlanCacheStatistics firstCold = executor.queryPlanCacheStatistics();
    assertEquals(
        expectedWithoutPrincipal(), findPrincipal(executor, projection, projectWithoutPrincipalId));
    QueryPlanCacheStatistics firstWarm = executor.queryPlanCacheStatistics();
    assertEquals(firstBefore.missCount() + 1, firstCold.missCount());
    assertEquals(firstCold.hitCount() + 1, firstWarm.hitCount());

    QueryPlanCacheStatistics secondBefore = secondExecutor.queryPlanCacheStatistics();
    assertEquals(
        expectedWithPrincipal(),
        findPrincipal(secondExecutor, projection, projectWithPrincipalId));
    QueryPlanCacheStatistics secondWarm = secondExecutor.queryPlanCacheStatistics();
    assertEquals(secondBefore.missCount() + 1, secondWarm.missCount());

    int taskCount = 12;
    CountDownLatch ready = new CountDownLatch(taskCount);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(taskCount);
    try {
      List<Future<ProjectPrincipalView>> results =
          java.util.stream.IntStream.range(0, taskCount)
              .mapToObj(
                  index ->
                      workers.submit(
                          () -> {
                            ready.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                              throw new AssertionError("timed out awaiting concurrent projection use");
                            }
                            assertSame(projection, ProjectPrincipalViewPro.all());
                            SkisExecutor target = index % 2 == 0 ? executor : secondExecutor;
                            long projectId =
                                index % 4 < 2
                                    ? projectWithPrincipalId
                                    : projectWithoutPrincipalId;
                            return findPrincipal(target, projection, projectId);
                          }))
              .toList();
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      QueryPlanCacheStatistics concurrentFirstBefore = executor.queryPlanCacheStatistics();
      QueryPlanCacheStatistics concurrentSecondBefore = secondExecutor.queryPlanCacheStatistics();
      release.countDown();

      for (int index = 0; index < results.size(); index++) {
        ProjectPrincipalView expected =
            index % 4 < 2 ? expectedWithPrincipal() : expectedWithoutPrincipal();
        assertEquals(expected, results.get(index).get(10, TimeUnit.SECONDS));
      }

      QueryPlanCacheStatistics concurrentFirstAfter = executor.queryPlanCacheStatistics();
      QueryPlanCacheStatistics concurrentSecondAfter = secondExecutor.queryPlanCacheStatistics();
      assertEquals(concurrentFirstBefore.missCount(), concurrentFirstAfter.missCount());
      assertEquals(concurrentSecondBefore.missCount(), concurrentSecondAfter.missCount());
      assertEquals(concurrentFirstBefore.hitCount() + 6, concurrentFirstAfter.hitCount());
      assertEquals(concurrentSecondBefore.hitCount() + 6, concurrentSecondAfter.hitCount());
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }

  private static ProjectPrincipalView findPrincipal(
      SkisExecutor target, ProjectPrincipalViewPro projection, long projectId) {
    return target
        .select(projection)
        .from(PROJECT)
        .leftJoin(EMPLOYEE)
        .on(PROJECT.principalId().eq(EMPLOYEE.id()))
        .where(PROJECT.id().eq(projectId))
        .fetchOne()
        .orElseThrow();
  }

  private ProjectPrincipalView expectedWithPrincipal() {
    return new ProjectPrincipalView(
        projectWithPrincipalId, "Project with principal", employeeId, "Ada");
  }

  private ProjectPrincipalView expectedWithoutPrincipal() {
    return new ProjectPrincipalView(
        projectWithoutPrincipalId, "Project without principal", null, null);
  }

  private void insertRows() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement employee =
            connection.prepareStatement(
                "INSERT INTO \"CL_EMPLOYEE\" (\"id\", \"name\") VALUES (?, ?)");
        PreparedStatement project =
            connection.prepareStatement(
                "INSERT INTO \"CL_PROJECT\" (\"id\", \"name\", \"principal_id\") VALUES (?, ?, ?)")) {
      employee.setLong(1, employeeId);
      employee.setString(2, "Ada");
      employee.executeUpdate();

      project.setLong(1, projectWithPrincipalId);
      project.setString(2, "Project with principal");
      project.setLong(3, employeeId);
      project.executeUpdate();
      project.setLong(1, projectWithoutPrincipalId);
      project.setString(2, "Project without principal");
      project.setNull(3, java.sql.Types.BIGINT);
      project.executeUpdate();
    }
  }

  private static void prepareSchema(DataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS "CL_EMPLOYEE" (
            "id" BIGINT PRIMARY KEY,
            "name" VARCHAR(200) NOT NULL
          )
          """);
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS "CL_PROJECT" (
            "id" BIGINT PRIMARY KEY,
            "name" VARCHAR(200) NOT NULL,
            "principal_id" BIGINT NULL
          )
          """);
    }
  }
}
