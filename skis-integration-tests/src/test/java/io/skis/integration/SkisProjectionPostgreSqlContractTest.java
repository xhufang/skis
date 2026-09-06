package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.skis.core.ExecutionOptions;
import io.skis.dialect.SqlExceptionCategory;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.mutation.MutationException;
import io.skis.mutation.OptimisticLockException;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.query.QueryCursor;
import io.skis.query.SelectQuery;
import io.skis.query.Slice;
import io.skis.query.SliceRequest;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.PetSummary;
import io.skis.testmodel.pet.skis.PetMeta;
import io.skis.testmodel.pet.skis.PetSummaryProjection;
import io.skis.testmodel.pet.skis.PetTable;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
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
              .select(PetSummaryProjection.of(pet.id(), pet.name(), pet.weight()))
              .from(pet)
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

  @Test
  void classifiesARealPostgreSqlDuplicateKeyFailure() throws Exception {
    PGSimpleDataSource dataSource = configuredDataSource();
    prepareSchema(dataSource);
    long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    ExecutionOptions defaults =
        ExecutionOptions.builder()
            .statementTimeout(Duration.ofSeconds(5))
            .fetchSize(32)
            .maxRows(100)
            .queryTag("postgresql.contract")
            .build();
    SkisExecutor executor =
        SkisExecutorFactory.builder()
            .dataSource(dataSource)
            .dialect(PostgreSqlDialect.INSTANCE)
            .executionOptions(defaults)
            .build();
    Pet pet = new Pet(id, "Mimi", new BigDecimal("12.50"), false, null, "ignored");

    try {
      executor.insert(PetMeta.ENTITY, pet);
      MutationException failure =
          assertThrows(MutationException.class, () -> executor.insert(PetMeta.ENTITY, pet));

      SQLException sqlFailure = (SQLException) failure.getCause();
      assertEquals(SqlExceptionCategory.DUPLICATE_KEY, failure.category());
      assertEquals(
          SqlExceptionCategory.DUPLICATE_KEY,
          PostgreSqlDialect.INSTANCE.exceptionClassifier().classify(sqlFailure));
    } finally {
      deletePet(dataSource, id);
    }
  }

  @Test
  void executesPageOffsetKeysetAndCursorContractsAgainstPostgreSql() throws Exception {
    PGSimpleDataSource dataSource = configuredDataSource();
    prepareSchema(dataSource);
    List<Long> ids =
        java.util.stream.Stream.generate(
                () -> UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE)
            .distinct()
            .limit(5)
            .sorted()
            .toList();
    SkisExecutor executor = SkisExecutorFactory.create(dataSource, PostgreSqlDialect.INSTANCE);

    try {
      for (int index = 0; index < ids.size(); index++) {
        executor.insert(
            PetMeta.ENTITY,
            new Pet(
                ids.get(index),
                "page-" + index,
                new BigDecimal("10.00"),
                false,
                null,
                "ignored"));
      }
      PetTable pet = PetTable.PET;
      SelectQuery<Pet, Pet> query =
          executor
              .selectFrom(pet)
              .where(pet.id().in(ids))
              .orderBy(pet.id().asc());

      Page<Pet> page = query.fetchPage(PageRequest.page(1, 2));
      Slice<Pet> offset = query.fetchSlice(SliceRequest.offset(0, 2));
      Slice<Pet> keysetFirst = query.fetchSlice(SliceRequest.keysetFirst(2));
      Slice<Pet> keysetSecond =
          query.fetchSlice(
              SliceRequest.resume(keysetFirst.nextContinuation().orElseThrow(), 2));
      List<Long> cursorIds = new java.util.ArrayList<>();
      try (QueryCursor<Pet> cursor = query.cursor()) {
        while (cursor.advance()) {
          cursorIds.add(cursor.current().id());
        }
      }

      assertEquals(ids.subList(2, 4), page.items().stream().map(Pet::id).toList());
      assertEquals(5, page.totalElements());
      assertEquals(3, page.totalPages());
      assertEquals(ids.subList(0, 2), offset.items().stream().map(Pet::id).toList());
      assertTrue(offset.hasNext());
      assertEquals(ids.subList(0, 2), keysetFirst.items().stream().map(Pet::id).toList());
      assertEquals(ids.subList(2, 4), keysetSecond.items().stream().map(Pet::id).toList());
      assertEquals(ids, cursorIds);
    } finally {
      for (long id : ids) {
        deletePet(dataSource, id);
      }
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
