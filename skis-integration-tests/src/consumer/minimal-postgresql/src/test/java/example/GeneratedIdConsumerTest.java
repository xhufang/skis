package example;

import static org.junit.jupiter.api.Assertions.*;

import example.skis.GeneratedTicketRuntimeModel;
import example.skis.GeneratedTicketTable;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.mutation.InsertResult;
import io.skis.runtime.Skis;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class GeneratedIdConsumerTest {
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
      InsertResult<GeneratedTicket> result = executor.insertResult(
          GeneratedTicketTable.generatedTicketTable, new GeneratedTicket(null, "consumer"));
      Long id = result.entity().id();
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
