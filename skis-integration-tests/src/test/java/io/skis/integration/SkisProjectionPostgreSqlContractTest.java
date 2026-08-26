package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.mutation.OptimisticLockException;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.PetSummary;
import io.skis.testmodel.pet.skis.PetMeta;
import io.skis.testmodel.pet.skis.PetTable;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class SkisProjectionPostgreSqlContractTest {

  @Test
  void executesScalarAndGeneratedProjectionContractsAgainstPostgreSql() throws Exception {
    PGSimpleDataSource dataSource = configuredDataSource();
    prepareSchema(dataSource);
    long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    String name = "Mimi'; DROP TABLE \"shelter\".\"pet\"; --";
    SkisExecutor executor = SkisExecutorFactory.create(dataSource, PostgreSqlDialect.INSTANCE);

    try {
      executor.insert(
          PetMeta.ENTITY,
          new Pet(id, name, new BigDecimal("12.50"), false, null, "ignored"));
      PetTable pet = PetTable.PET;
      List<String> names =
          executor.select(pet.name()).from(pet).where(pet.name().eq(name)).fetchList();
      PetSummary projected =
          executor
              .selectProjection(pet, PetSummary.class)
              .where(pet.id().eq(id))
              .fetchOne()
              .orElseThrow();

      assertEquals(List.of(name), names);
      assertEquals(new PetSummary(id, name, new BigDecimal("12.50")), projected);
      assertTrue(executor.findById(PetMeta.ENTITY, id).isPresent());
    } finally {
      deletePet(dataSource, id);
    }
  }

  @Test
  void executesMutationOptimisticLockAndTransactionContractsAgainstPostgreSql() throws Exception {
    PGSimpleDataSource dataSource = configuredDataSource();
    prepareSchema(dataSource);
    long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    long rolledBackId = id == Long.MAX_VALUE ? id - 1 : id + 1;
    SkisExecutor executor = SkisExecutorFactory.create(dataSource, PostgreSqlDialect.INSTANCE);

    try {
      executor.insert(
          PetMeta.ENTITY,
          new Pet(id, "Mimi", new BigDecimal("12.50"), false, null, "ignored"));
      Pet stored = executor.findById(PetMeta.ENTITY, id).orElseThrow();
      assertEquals(Long.valueOf(0L), stored.version());

      Pet update =
          new Pet(id, "Mimi", new BigDecimal("13.25"), true, stored.version(), "ignored");
      assertEquals(1, executor.updateById(PetMeta.ENTITY, update));
      Pet updated = executor.findById(PetMeta.ENTITY, id).orElseThrow();
      assertEquals(new BigDecimal("13.25"), updated.weight());
      assertTrue(updated.adopted());
      assertEquals(Long.valueOf(1L), updated.version());
      assertThrows(
          OptimisticLockException.class, () -> executor.updateById(PetMeta.ENTITY, update));

      assertThrows(
          IllegalStateException.class,
          () ->
              executor.inTransaction(
                  session -> {
                    session.insert(
                        PetMeta.ENTITY,
                        new Pet(
                            rolledBackId,
                            "Rollback",
                            new BigDecimal("1.00"),
                            false,
                            null,
                            "ignored"));
                    throw new IllegalStateException("force rollback");
                  }));
      assertFalse(executor.findById(PetMeta.ENTITY, rolledBackId).isPresent());

      assertEquals(1, executor.deleteById(PetMeta.ENTITY, id));
      assertTrue(executor.findById(PetMeta.ENTITY, id).isEmpty());
    } finally {
      deletePet(dataSource, id);
      deletePet(dataSource, rolledBackId);
    }
  }

  private static PGSimpleDataSource configuredDataSource() {
    String url = System.getenv("SKIS_POSTGRES_URL");
    assumeTrue(url != null && url.startsWith("jdbc:postgresql:"));
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(url);
    dataSource.setUser(environmentOrDefault("SKIS_POSTGRES_USER", "skis"));
    dataSource.setPassword(environmentOrDefault("SKIS_POSTGRES_PASSWORD", "skis"));
    return dataSource;
  }

  private static void prepareSchema(PGSimpleDataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS \"shelter\"");
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS "shelter"."pet" (
            "id" BIGINT PRIMARY KEY,
            "pet_name" VARCHAR(200) NOT NULL,
            "weight" DECIMAL(6, 2) NOT NULL,
            "adopted" BOOLEAN NOT NULL,
            "version" BIGINT NOT NULL
          )
          """);
    }
  }

  private static void deletePet(PGSimpleDataSource dataSource, long id) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("DELETE FROM \"shelter\".\"pet\" WHERE \"id\" = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    }
  }

  private static String environmentOrDefault(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
