package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.skis.dialect.h2.H2Dialect;
import io.skis.query.Projection;
import io.skis.query.QueryPlanCacheStatistics;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.PetSummary;
import io.skis.testmodel.pet.skis.PetMeta;
import io.skis.testmodel.pet.skis.PetSummaryProjection;
import io.skis.testmodel.pet.skis.PetTable;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class SkisProjectionIntegrationTest {

  @Test
  void selectsScalarsAndUserRecordsWithoutReadingTheFullEntity() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor =
        SkisExecutorFactory.builder()
            .dataSource(dataSource)
            .dialect(H2Dialect.INSTANCE)
            .planCacheMaximumSize(2)
            .planCacheExpireAfterAccess(Duration.ofMinutes(5))
            .build();
    Pet pet = new Pet(7L, "Mimi", new BigDecimal("12.50"), false, null, "ignored");
    executor.insert(PetMeta.ENTITY, pet);

    List<String> names =
        executor
            .select(PetTable.PET.name())
            .from(PetTable.PET)
            .where(PetTable.PET.id().eq(7L))
            .fetch();
    Projection<Pet, PetSummary> summary =
        PetSummaryProjection.of(
            PetTable.PET.id(),
            PetTable.PET.name(),
            PetTable.PET.weight());
    PetSummary projected =
        executor
            .select(summary)
            .from(PetTable.PET)
            .where(PetTable.PET.id().eq(7L))
            .fetchOne()
            .orElseThrow();
    String transactionalName =
        executor.inTransaction(
            session ->
                session
                    .select(PetTable.PET.name())
                    .from(PetTable.PET)
                    .where(PetTable.PET.id().eq(7L))
                    .fetchOne()
                    .orElseThrow());

    assertEquals(List.of("Mimi"), names);
    assertEquals(new PetSummary(7L, "Mimi", new BigDecimal("12.50")), projected);
    assertEquals("Mimi", transactionalName);
    assertEquals(
        new QueryPlanCacheStatistics(1, 2, 0, 0, 2, 2),
        executor.queryPlanCacheStatistics());
    executor.clearQueryPlanCache();
    assertEquals(0, executor.queryPlanCacheStatistics().size());
    assertEquals(2, executor.queryPlanCacheStatistics().invalidationCount());
  }

  private static DataSource database() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:skis_projection_"
            + UUID.randomUUID().toString().replace('-', '_')
            + ";DB_CLOSE_DELAY=-1");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA \"shelter\"");
      statement.execute(
          """
          CREATE TABLE "shelter"."pet" (
            "id" BIGINT PRIMARY KEY,
            "pet_name" VARCHAR(200) NOT NULL,
            "weight" DECIMAL(6, 2) NOT NULL,
            "adopted" BOOLEAN NOT NULL,
            "version" BIGINT NOT NULL
          )
          """);
    }
    return dataSource;
  }
}
