package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.ExecutionOptions;
import io.skis.core.TransactionException;
import io.skis.dialect.spi.SqlExceptionCategory;
import io.skis.dialect.h2.H2Dialect;
import io.skis.jdbc.JdbcExecutionException;
import io.skis.mutation.OptimisticLockException;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.skis.PetTable;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class SkisMutationTransactionTest {

  @Test
  void insertsUpdatesVersionsAndDeletesThroughGeneratedFastPaths() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = Skis.create(dataSource, H2Dialect.INSTANCE);
    Pet inserted = new Pet(7L, "Mimi", new BigDecimal("12.50"), false, null, "ignored");

    assertEquals(1, executor.insertInto(PetTable.petTable).value(inserted).write());
    Pet stored = executor.findById(PetTable.petTable, 7L).orElseThrow();
    assertEquals(Long.valueOf(0L), stored.version());

    Pet update = new Pet(7L, "Mimi", new BigDecimal("13.25"), true, 0L, "ignored");
    assertEquals(1, executor.updateById(PetTable.petTable, update));
    Pet updated = executor.findById(PetTable.petTable, 7L).orElseThrow();
    assertEquals(new BigDecimal("13.25"), updated.weight());
    assertTrue(updated.adopted());
    assertEquals(Long.valueOf(1L), updated.version());

    assertThrows(
        OptimisticLockException.class, () -> executor.updateById(PetTable.petTable, update));
    assertEquals(1, executor.deleteById(PetTable.petTable, 7L));
    assertTrue(executor.findById(PetTable.petTable, 7L).isEmpty());
  }

  @Test
  void aliasesKeepTheCanonicalEntityCrudTarget() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = Skis.create(dataSource, H2Dialect.INSTANCE);
    PetTable alias = PetTable.petTable.as("crud_pet");
    Pet inserted = new Pet(70L, "Alias", new BigDecimal("7.00"), false, null, "ignored");

    assertEquals(1, executor.insertInto(alias).value(inserted).write());
    Pet stored = executor.findById(alias, inserted.id()).orElseThrow();
    Pet changed =
        new Pet(
            stored.id(),
            "Alias Updated",
            stored.weight(),
            stored.adopted(),
            stored.version(),
            stored.displayLabel());
    assertEquals(1, executor.updateById(alias, changed));
    assertEquals("Alias Updated", executor.findById(alias, inserted.id()).orElseThrow().name());
    assertEquals(1, executor.deleteById(alias, inserted.id()));
    assertTrue(executor.findById(alias, inserted.id()).isEmpty());
  }

  @Test
  void commitsOneConnectionAndRunsAfterCommitOnlyAfterSuccess() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = Skis.create(dataSource, H2Dialect.INSTANCE);
    AtomicInteger callbacks = new AtomicInteger();
    Pet pet = new Pet(8L, "Fifi", new BigDecimal("8.00"), false, 0L, "ignored");

    Pet visibleInside =
        executor.inTransaction(
            session -> {
              session.insertInto(PetTable.petTable).value(pet).write();
              session.afterCommit(callbacks::incrementAndGet);
              return session.findById(PetTable.petTable, 8L).orElseThrow();
            });

    assertEquals("Fifi", visibleInside.name());
    assertEquals(1, callbacks.get());
    assertTrue(executor.findById(PetTable.petTable, 8L).isPresent());
  }

  @Test
  void advancesVersionWithoutAddingAHiddenReadWhenExpectedVersionIsMissing() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = Skis.create(dataSource, H2Dialect.INSTANCE);
    Pet inserted = new Pet(11L, "Lulu", new BigDecimal("4.00"), false, null, "ignored");
    executor.insertInto(PetTable.petTable).value(inserted).write();
    Pet update = new Pet(11L, "Lulu", new BigDecimal("4.25"), true, null, "ignored");

    assertEquals(1, executor.updateById(PetTable.petTable, update));

    Pet stored = executor.findById(PetTable.petTable, 11L).orElseThrow();
    assertEquals(Long.valueOf(1L), stored.version());
    assertTrue(stored.adopted());
  }

  @Test
  void rollsBackAllWritesAndDiscardsAfterCommitCallbacksOnFailure() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = Skis.create(dataSource, H2Dialect.INSTANCE);
    AtomicInteger callbacks = new AtomicInteger();
    Pet pet = new Pet(9L, "Nori", new BigDecimal("6.50"), false, 0L, "ignored");

    assertThrows(
        IllegalStateException.class,
        () ->
            executor.inTransaction(
                session -> {
                  session.insertInto(PetTable.petTable).value(pet).write();
                  session.afterCommit(callbacks::incrementAndGet);
                  throw new IllegalStateException("force rollback");
                }));

    assertEquals(0, callbacks.get());
    assertFalse(executor.findById(PetTable.petTable, 9L).isPresent());
  }

  @Test
  void reportsAfterCommitFailureWithoutRollingBackTheCommittedTransaction() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = Skis.create(dataSource, H2Dialect.INSTANCE);
    AtomicInteger callbacks = new AtomicInteger();
    Pet pet = new Pet(10L, "Kiki", new BigDecimal("5.75"), false, 0L, "ignored");

    TransactionException failure =
        assertThrows(
            TransactionException.class,
            () ->
                executor.inTransaction(
                    session -> {
                      session.insertInto(PetTable.petTable).value(pet).write();
                      session.afterCommit(
                          () -> {
                            callbacks.incrementAndGet();
                            throw new IllegalStateException("publication failed");
                          });
                      session.afterCommit(callbacks::incrementAndGet);
                      return null;
                    }));

    assertTrue(failure.getMessage().contains("transaction committed"));
    assertEquals(2, callbacks.get());
    assertTrue(executor.findById(PetTable.petTable, 10L).isPresent());
  }

  @Test
  void appliesExecutorDefaultsPerStatementOverridesAndTransactionRebinding() throws Exception {
    DataSource dataSource = database();
    ExecutionOptions defaults =
        ExecutionOptions.builder()
            .statementTimeout(Duration.ofSeconds(5))
            .fetchSize(16)
            .maxRows(1)
            .queryTag("h2.contract")
            .build();
    SkisExecutor executor =
        Skis.builder(dataSource).dialect(H2Dialect.INSTANCE).executionOptions(defaults).build();
    executor.insertInto(PetTable.petTable).value(new Pet(21L, "Mimi", new BigDecimal("12.50"), false, null, "ignored")).write();
    executor.insertInto(PetTable.petTable).value(new Pet(22L, "Fifi", new BigDecimal("8.00"), false, null, "ignored")).write();
    ExecutionOptions unlimited = ExecutionOptions.builder().maxRows(0).clearQueryTag().build();

    assertEquals(1, executor.selectFrom(PetTable.petTable).fetchList().size());
    assertEquals(
        2, executor.selectFrom(PetTable.petTable).withOptions(unlimited).fetchList().size());

    executor.inTransaction(
        session -> {
          assertEquals(1, session.selectFrom(PetTable.petTable).fetchList().size());
          assertEquals(
              2, session.selectFrom(PetTable.petTable).withOptions(unlimited).fetchList().size());
          return null;
        });

    ExecutionOptions sessionDefaults =
        ExecutionOptions.builder().maxRows(0).clearQueryTag().build();
    ExecutionOptions oneRow = ExecutionOptions.builder().maxRows(1).build();
    executor.inTransaction(
        sessionDefaults,
        session -> {
          assertEquals(2, session.selectFrom(PetTable.petTable).fetchList().size());
          assertEquals(
              1, session.selectFrom(PetTable.petTable).withOptions(oneRow).fetchList().size());
          return null;
        });
  }

  @Test
  void classifiesARealH2DuplicateKeyFailure() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = Skis.create(dataSource, H2Dialect.INSTANCE);
    Pet pet = new Pet(31L, "Mimi", new BigDecimal("12.50"), false, null, "ignored");
    executor.insertInto(PetTable.petTable).value(pet).write();

    JdbcExecutionException failure =
        assertThrows(JdbcExecutionException.class, () -> executor.insertInto(PetTable.petTable).value(pet).write());

    SQLException sqlFailure = (SQLException) failure.getCause();
    assertEquals(SqlExceptionCategory.DUPLICATE_KEY, failure.category());
    assertEquals(
        SqlExceptionCategory.DUPLICATE_KEY,
        H2Dialect.INSTANCE.exceptionClassifier().classify(sqlFailure));
  }

  private static DataSource database() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:skis_"
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
