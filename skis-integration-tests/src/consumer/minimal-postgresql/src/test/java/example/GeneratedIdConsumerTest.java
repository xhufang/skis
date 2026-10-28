package example;

import static org.junit.jupiter.api.Assertions.*;

import example.skis.GeneratedTicketRuntimeModel;
import example.skis.GeneratedTicketTable;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.mutation.Written;
import io.skis.runtime.Skis;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class GeneratedIdConsumerTest {
  @Test
  void publishedApiRemovesOldInsertionEntrypoints() {
    for (var method : io.skis.runtime.SkisExecutor.class.getMethods()) {
      assertFalse(java.util.Set.of("insert", "insertResult", "insertReturning", "batchInsert").contains(method.getName()));
    }
    assertThrows(ClassNotFoundException.class, () -> Class.forName("io.skis.mutation.InsertResult"));
  }

  @Test
  void readsTheRequestedIdentityColumnThroughPublishedPublicArtifacts() throws Exception {
    var source = new PGSimpleDataSource();
    source.setURL(Objects.requireNonNull(System.getenv("SKIS_POSTGRES_URL")));
    source.setUser(Objects.requireNonNull(System.getenv("SKIS_POSTGRES_USER")));
    source.setPassword(Objects.requireNonNull(System.getenv("SKIS_POSTGRES_PASSWORD")));
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS \"generated_ticket\" ("
          + "\"id\" BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, \"name\" VARCHAR(100))");
      var executor = Skis.builder(source).dialect(PostgreSqlDialect.INSTANCE)
          .runtimeModels(List.of(GeneratedTicketRuntimeModel.MODEL)).build();
      Written<GeneratedTicket> result = executor.insertInto(GeneratedTicketTable.generatedTicketTable).value(new GeneratedTicket(null, "consumer")).returning().write();
      Long id = result.get().id();
      assertNotNull(id);
      try {
        assertEquals("consumer", executor.findById(GeneratedTicketTable.generatedTicketTable, id)
            .orElseThrow().name());
      } finally {
        assertEquals(1, executor.deleteById(GeneratedTicketTable.generatedTicketTable, id));
      }
    }
  }
}
