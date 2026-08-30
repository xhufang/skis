package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.core.ExecutionOptions;
import io.skis.core.TransactionException;
import io.skis.dialect.SqlExceptionCategory;
import io.skis.dialect.h2.H2Dialect;
import io.skis.mutation.MutationException;
import io.skis.mutation.OptimisticLockException;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.skis.PetMeta;
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
    SkisExecutor executor = SkisExecutorFactory.create(dataSource, H2Dialect.INSTANCE);
    Pet inserted = new Pet(7L, "Mimi", new BigDecimal("12.50"), false, null, "ignored");

    assertEquals(1, executor.insert(PetMeta.ENTITY, inserted));
    Pet stored = executor.findById(PetMeta.ENTITY, 7L).orElseThrow();
    assertEquals(Long.valueOf(0L), stored.version());

    Pet update = new Pet(7L, "Mimi", new BigDecimal("13.25"), true, 0L, "ignored");
    assertEquals(1, executor.updateById(PetMeta.ENTITY, update));
    Pet updated = executor.findById(PetMeta.ENTITY, 7L).orElseThrow();
    assertEquals(new BigDecimal("13.25"), updated.weight());
    assertTrue(updated.adopted());
    assertEquals(Long.valueOf(1L), updated.version());

    assertThrows(
        OptimisticLockException.class, () -> executor.updateById(PetMeta.ENTITY, update));
    assertEquals(1, executor.deleteById(PetMeta.ENTITY, 7L));
    assertTrue(executor.findById(PetMeta.ENTITY, 7L).isEmpty());
  }

  @Test
  void commitsOneConnectionAndRunsAfterCommitOnlyAfterSuccess() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = SkisExecutorFactory.create(dataSource, H2Dialect.INSTANCE);
    AtomicInteger callbacks = new AtomicInteger();
    Pet pet = new Pet(8L, "Fifi", new BigDecimal("8.00"), false, 0L, "ignored");

    Pet visibleInside =
        executor.inTransaction(
            session -> {
              session.insert(PetMeta.ENTITY, pet);
              session.afterCommit(callbacks::incrementAndGet);
              return session.findById(PetMeta.ENTITY, 8L).orElseThrow();
            });

    assertEquals("Fifi", visibleInside.name());
    assertEquals(1, callbacks.get());
    assertTrue(executor.findById(PetMeta.ENTITY, 8L).isPresent());
  }

  @Test
  void advancesVersionWithoutAddingAHiddenReadWhenExpectedVersionIsMissing() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = SkisExecutorFactory.create(dataSource, H2Dialect.INSTANCE);
    Pet inserted = new Pet(11L, "Lulu", new BigDecimal("4.00"), false, null, "ignored");
    executor.insert(PetMeta.ENTITY, inserted);
    Pet update = new Pet(11L, "Lulu", new BigDecimal("4.25"), true, null, "ignored");

    assertEquals(1, executor.updateById(PetMeta.ENTITY, update));

    Pet stored = executor.findById(PetMeta.ENTITY, 11L).orElseThrow();
    assertEquals(Long.valueOf(1L), stored.version());
    assertTrue(stored.adopted());
  }

  @Test
  void rollsBackAllWritesAndDiscardsAfterCommitCallbacksOnFailure() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = SkisExecutorFactory.create(dataSource, H2Dialect.INSTANCE);
    AtomicInteger callbacks = new AtomicInteger();
    Pet pet = new Pet(9L, "Nori", new BigDecimal("6.50"), false, 0L, "ignored");

    assertThrows(
        IllegalStateException.class,
        () ->
            executor.inTransaction(
                session -> {
                  session.insert(PetMeta.ENTITY, pet);
                  session.afterCommit(callbacks::incrementAndGet);
                  throw new IllegalStateException("force rollback");
                }));

    assertEquals(0, callbacks.get());
    assertFalse(executor.findById(PetMeta.ENTITY, 9L).isPresent());
  }

  @Test
  void reportsAfterCommitFailureWithoutRollingBackTheCommittedTransaction() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = SkisExecutorFactory.create(dataSource, H2Dialect.INSTANCE);
    AtomicInteger callbacks = new AtomicInteger();
    Pet pet = new Pet(10L, "Kiki", new BigDecimal("5.75"), false, 0L, "ignored");

    TransactionException failure =
        assertThrows(
            TransactionException.class,
            () ->
                executor.inTransaction(
                    session -> {
                      session.insert(PetMeta.ENTITY, pet);
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
    assertTrue(executor.findById(PetMeta.ENTITY, 10L).isPresent());
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
        SkisExecutorFactory.builder()
            .dataSource(dataSource)
            .dialect(H2Dialect.INSTANCE)
            .executionOptions(defaults)
            .build();
    executor.insert(
        PetMeta.ENTITY,
        new Pet(21L, "Mimi", new BigDecimal("12.50"), false, null, "ignored"));
    executor.insert(
        PetMeta.ENTITY,
        new Pet(22L, "Fifi", new BigDecimal("8.00"), false, null, "ignored"));
    ExecutionOptions unlimited =
        ExecutionOptions.builder().maxRows(0).clearQueryTag().build();

    assertEquals(1, executor.selectFrom(PetTable.PET).fetchList().size());
    assertEquals(
        2,
        executor
            .selectFrom(PetTable.PET)
            .withOptions(unlimited)
            .fetchList()
            .size());

    executor.inTransaction(
        session -> {
          assertEquals(1, session.selectFrom(PetTable.PET).fetchList().size());
          assertEquals(
              2,
              session
                  .selectFrom(PetTable.PET)
                  .withOptions(unlimited)
                  .fetchList()
                  .size());
          return null;
        });

    ExecutionOptions sessionDefaults =
        ExecutionOptions.builder().maxRows(0).clearQueryTag().build();
    ExecutionOptions oneRow = ExecutionOptions.builder().maxRows(1).build();
    executor.inTransaction(
        sessionDefaults,
        session -> {
          assertEquals(2, session.selectFrom(PetTable.PET).fetchList().size());
          assertEquals(
              1,
              session
                  .selectFrom(PetTable.PET)
                  .withOptions(oneRow)
                  .fetchList()
                  .size());
          return null;
        });
  }

  @Test
  void classifiesARealH2DuplicateKeyFailure() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = SkisExecutorFactory.create(dataSource, H2Dialect.INSTANCE);
    Pet pet = new Pet(31L, "Mimi", new BigDecimal("12.50"), false, null, "ignored");
    executor.insert(PetMeta.ENTITY, pet);

    MutationException failure =
        assertThrows(MutationException.class, () -> executor.insert(PetMeta.ENTITY, pet));

    SQLException sqlFailure = (SQLException) failure.getCause();
    assertEquals(SqlExceptionCategory.DUPLICATE_KEY, failure.category());
    assertEquals(
        SqlExceptionCategory.DUPLICATE_KEY,
        H2Dialect.INSTANCE.exceptionClassifier().classify(sqlFailure));
  }

  private static DataSource database() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:skis_" + UUID.randomUUID().toString().replace('-', '_') + ";DB_CLOSE_DELAY=-1");
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
