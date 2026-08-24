package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.h2.H2Dialect;
import io.skis.mutation.OptimisticLockException;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.skis.PetMeta;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
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
