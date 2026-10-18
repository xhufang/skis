package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import example.skis.PetSummaryPro;
import example.skis.PetTable;
import io.skis.dialect.h2.H2Dialect;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import java.sql.Connection;
import java.sql.Statement;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class MinimalConsumerTest {

  @Test
  void resolvesBomRunsAptAndUsesRuntimeDialectAndDriver() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:minimal_consumer;DB_CLOSE_DELAY=-1");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE "pet" (
            "id" BIGINT PRIMARY KEY,
            "pet_name" VARCHAR(200) NOT NULL,
            "version" BIGINT NOT NULL
          )
          """);
    }

    SkisExecutor executor = Skis.create(dataSource, H2Dialect.INSTANCE);
    executor.insert(PetTable.petTable, new Pet(1L, "Mimi", null));

    Pet stored = executor.findById(PetTable.petTable, 1L).orElseThrow();
    assertEquals("Mimi", stored.name());

    PetSummary summary =
        executor
            .select(PetSummaryPro.of(PetTable.petTable.id(), PetTable.petTable.name()))
            .from(PetTable.petTable)
            .where(PetTable.petTable.id().eq(1L))
            .fetchOne()
            .orElseThrow();
    assertEquals(new PetSummary(1L, "Mimi"), summary);

    assertEquals(1, executor.deleteById(PetTable.petTable, 1L));
    assertTrue(executor.findById(PetTable.petTable, 1L).isEmpty());
  }
}
