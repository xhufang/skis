package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import example.skis.PetSummaryPro;
import example.skis.PetTable;
import io.skis.core.spi.ExecutionListener;
import io.skis.core.spi.ExecutionOutcome;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.OperationCompletedEvent;
import io.skis.core.spi.StatementCompletedEvent;
import io.skis.core.spi.StatementPurpose;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.jdbc.JdbcExecutionException;
import io.skis.query.Page;
import io.skis.query.PageRequest;
import io.skis.query.SelectQuery;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class PostgreSqlConsumerTest {

  @Test
  void resolvesBomGeneratesMappingsAndExecutesAgainstTheRealDriver() throws Exception {
    PGSimpleDataSource dataSource = dataSource();
    prepareSchema(dataSource);
    List<StatementCompletedEvent> statements = new CopyOnWriteArrayList<>();
    List<OperationCompletedEvent> operations = new CopyOnWriteArrayList<>();
    SkisExecutor executor =
        Skis.builder(dataSource)
            .dialect(PostgreSqlDialect.INSTANCE)
            .listener(
                new ExecutionListener() {
                  @Override
                  public void statementCompleted(StatementCompletedEvent event) {
                    statements.add(event);
                  }

                  @Override
                  public void operationCompleted(OperationCompletedEvent event) {
                    operations.add(event);
                  }
                })
            .build();
    PetTable pet = PetTable.petTable;
    List<Long> ids =
        Stream.generate(() -> UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE)
            .distinct()
            .limit(4)
            .sorted()
            .toList();
    try {
      for (int index = 0; index < 3; index++) {
        executor.insert(pet, new Pet(ids.get(index), "sensitive-consumer-" + index, null));
      }
      assertEquals(
          "sensitive-consumer-0", executor.findById(pet, ids.getFirst()).orElseThrow().name());
      PetSummary summary =
          executor.select(PetSummaryPro.of(pet.id(), pet.name()))
              .from(pet)
              .where(pet.id().eq(ids.getFirst()))
              .fetchOne()
              .orElseThrow();
      assertEquals(new PetSummary(ids.getFirst(), "sensitive-consumer-0"), summary);

      statements.clear();
      operations.clear();
      SelectQuery<Pet> query =
          executor.selectFrom(pet).where(pet.id().in(ids)).orderBy(pet.id().asc());
      Page<Pet> page = query.fetchPage(PageRequest.page(0, 2));
      assertEquals(3L, page.totalElements());
      assertEquals(ids.subList(0, 2), page.items().stream().map(Pet::id).toList());
      assertEquals(
          List.of(StatementPurpose.PAGE_CONTENT, StatementPurpose.PAGE_COUNT),
          statements.stream().map(StatementCompletedEvent::purpose).toList());
      assertEquals(
          List.of(2L, 1L), statements.stream().map(StatementCompletedEvent::readRowCount).toList());
      assertEquals(1, operations.size());
      assertTrue(
          statements.stream()
              .allMatch(event -> event.operationId() == operations.getFirst().operationId()));
      assertFalse(statements.toString().contains("sensitive-consumer"));

      statements.clear();
      operations.clear();
      try (Stream<Pet> rows = query.stream()) {
        assertEquals(ids.subList(0, 3), rows.map(Pet::id).toList());
        assertEquals(1, operations.size());
        assertEquals(1, statements.size());
        assertEquals(ExecutionTermination.EXHAUSTED, operations.getFirst().termination());
        assertEquals(ExecutionTermination.EXHAUSTED, statements.getFirst().termination());
      }
      assertEquals(1, operations.size());
      assertEquals(1, statements.size());
      assertEquals(ExecutionTermination.EXHAUSTED, operations.getFirst().termination());
      assertEquals(3L, statements.getFirst().readRowCount());

      statements.clear();
      operations.clear();
      try (Stream<Pet> rows = query.stream()) {
        assertEquals(ids.getFirst().longValue(), rows.findFirst().orElseThrow().id());
        assertTrue(operations.isEmpty());
      }
      assertEquals(1, operations.size());
      assertEquals(ExecutionTermination.CLOSED_EARLY, operations.getFirst().termination());

      assertThrows(
          IllegalStateException.class,
          () -> executor.inTransaction(
              session -> {
                session.insert(pet, new Pet(ids.getLast(), "rolled-back-consumer", null));
                assertTrue(session.findById(pet, ids.getLast()).isPresent());
                throw new IllegalStateException("force rollback");
              }));
      assertTrue(executor.findById(pet, ids.getLast()).isEmpty());

      statements.clear();
      operations.clear();
      JdbcExecutionException failure =
          assertThrows(
              JdbcExecutionException.class,
              () -> executor.insert(pet, new Pet(ids.getFirst(), "sensitive-duplicate", null)));
      assertEquals("23505", failure.sqlState());
      SQLException sqlFailure = assertInstanceOf(SQLException.class, failure.getCause());
      assertEquals("23505", sqlFailure.getSQLState());
      String conflictingKey = ids.getFirst().toString();
      assertTrue(Objects.requireNonNull(sqlFailure.getMessage()).contains(conflictingKey));
      assertEquals(1, operations.size());
      assertEquals(1, statements.size());
      assertEquals(ExecutionOutcome.FAILED, operations.getFirst().outcome());
      assertEquals(ExecutionTermination.FAILED, statements.getFirst().termination());
      assertEquals(failure.diagnostics(), statements.getFirst().diagnostics());
      assertEquals(failure.diagnostics(), operations.getFirst().diagnostics());
      assertFalse(failure.getMessage().contains(conflictingKey));
      assertFalse(statements.toString().contains(conflictingKey));
      assertFalse(operations.toString().contains(conflictingKey));
      assertFalse(statements.toString().contains("sensitive-duplicate"));
    } finally {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement delete =
              connection.prepareStatement("DELETE FROM \"consumer\".\"pet\" WHERE \"id\" = ?")) {
        for (long id : ids) {
          delete.setLong(1, id);
          delete.executeUpdate();
        }
      }
    }
  }

  private static PGSimpleDataSource dataSource() {
    String url =
        Objects.requireNonNull(
            System.getenv("SKIS_POSTGRES_URL"),
            "SKIS_POSTGRES_URL is required by the independent PostgreSQL consumer");
    assertTrue(url.startsWith("jdbc:postgresql:"));
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(url);
    dataSource.setUser(
        Objects.requireNonNull(System.getenv("SKIS_POSTGRES_USER"), "SKIS_POSTGRES_USER"));
    dataSource.setPassword(
        Objects.requireNonNull(System.getenv("SKIS_POSTGRES_PASSWORD"), "SKIS_POSTGRES_PASSWORD"));
    return dataSource;
  }

  private static void prepareSchema(PGSimpleDataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS \"consumer\"");
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS "consumer"."pet" (
            "id" BIGINT PRIMARY KEY,
            "pet_name" VARCHAR(200) NOT NULL,
            "version" BIGINT NOT NULL
          )
          """);
    }
  }
}
