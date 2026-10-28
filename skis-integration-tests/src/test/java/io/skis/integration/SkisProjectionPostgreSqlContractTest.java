package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.ExecutionOptions;
import io.skis.dialect.spi.SqlExceptionCategory;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.jdbc.JdbcExecutionException;
import io.skis.mutation.OptimisticLockException;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.query.QueryCursor;
import io.skis.query.SelectQuery;
import io.skis.query.Slice;
import io.skis.query.SliceRequest;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import io.skis.testsupport.PostgresContract;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.PetSummary;
import io.skis.testmodel.pet.skis.PetSummaryPro;
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

@PostgresContract
class SkisProjectionPostgreSqlContractTest {

  private final PGSimpleDataSource postgres;

  SkisProjectionPostgreSqlContractTest(PGSimpleDataSource postgres) {
    this.postgres = postgres;
  }

  @Test
  void executesScalarAndGeneratedProjectionContractsAgainstPostgreSql() throws Exception {
    PGSimpleDataSource dataSource = configuredDataSource();
    prepareSchema(dataSource);
    long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    String name = "Mimi'; DROP TABLE \"shelter\".\"pet\"; --";
    SkisExecutor executor = Skis.create(dataSource, PostgreSqlDialect.INSTANCE);

    try {
      executor.insertInto(PetTable.petTable).value(new Pet(id, name, new BigDecimal("12.50"), false, null, "ignored")).write();
      PetTable pet = PetTable.petTable;
      List<String> names =
          executor.select(pet.name()).from(pet).where(pet.name().eq(name)).fetchList();
      PetSummary projected =
          executor
              .select(PetSummaryPro.of(pet.id(), pet.name(), pet.weight()))
              .from(pet)
              .where(pet.id().eq(id))
              .fetchOne()
              .orElseThrow();

      assertEquals(List.of(name), names);
      assertEquals(new PetSummary(id, name, new BigDecimal("12.50")), projected);
      assertTrue(executor.findById(PetTable.petTable, id).isPresent());
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
    SkisExecutor executor = Skis.create(dataSource, PostgreSqlDialect.INSTANCE);

    try {
      executor.insertInto(PetTable.petTable).value(new Pet(id, "Mimi", new BigDecimal("12.50"), false, null, "ignored")).write();
      Pet stored = executor.findById(PetTable.petTable, id).orElseThrow();
      assertEquals(Long.valueOf(0L), stored.version());

      Pet update = new Pet(id, "Mimi", new BigDecimal("13.25"), true, stored.version(), "ignored");
      assertEquals(1, executor.updateById(PetTable.petTable, update));
      Pet updated = executor.findById(PetTable.petTable, id).orElseThrow();
      assertEquals(new BigDecimal("13.25"), updated.weight());
      assertTrue(updated.adopted());
      assertEquals(Long.valueOf(1L), updated.version());
      assertThrows(
          OptimisticLockException.class, () -> executor.updateById(PetTable.petTable, update));

      assertThrows(
          IllegalStateException.class,
          () ->
              executor.inTransaction(
                  session -> {
                    session.insertInto(PetTable.petTable).value(new Pet(
                            rolledBackId,
                            "Rollback",
                            new BigDecimal("1.00"),
                            false,
                            null,
                            "ignored")).write();
                    throw new IllegalStateException("force rollback");
                  }));
      assertFalse(executor.findById(PetTable.petTable, rolledBackId).isPresent());

      assertEquals(1, executor.deleteById(PetTable.petTable, id));
      assertTrue(executor.findById(PetTable.petTable, id).isEmpty());
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
        Skis.builder(dataSource)
            .dialect(PostgreSqlDialect.INSTANCE)
            .executionOptions(defaults)
            .build();
    Pet pet = new Pet(id, "Mimi", new BigDecimal("12.50"), false, null, "ignored");

    try {
      executor.insertInto(PetTable.petTable).value(pet).write();
      JdbcExecutionException failure =
          assertThrows(JdbcExecutionException.class, () -> executor.insertInto(PetTable.petTable).value(pet).write());

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
    SkisExecutor executor = Skis.create(dataSource, PostgreSqlDialect.INSTANCE);

    try {
      for (int index = 0; index < ids.size(); index++) {
        executor.insertInto(PetTable.petTable).value(new Pet(
                ids.get(index), "page-" + index, new BigDecimal("10.00"), false, null, "ignored")).write();
      }
      PetTable pet = PetTable.petTable;
      SelectQuery<Pet> query =
          executor.selectFrom(pet).where(pet.id().in(ids)).orderBy(pet.id().asc());

      Page<Pet> page = query.fetchPage(PageRequest.page(1, 2));
      Slice<Pet> offset = query.fetchSlice(SliceRequest.offset(0, 2));
      Slice<Pet> keysetFirst = query.fetchSlice(SliceRequest.keysetFirst(2));
      Slice<Pet> keysetSecond =
          query.fetchSlice(SliceRequest.resume(keysetFirst.nextContinuation().orElseThrow(), 2));
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

  private PGSimpleDataSource configuredDataSource() {
    return postgres;
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

}
