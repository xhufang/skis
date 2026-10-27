package example;

import static org.junit.jupiter.api.Assertions.*;

import example.skis.GeneratedTicketRuntimeModel;
import example.skis.GeneratedTicketTable;
import io.skis.dialect.h2.H2Dialect;
import io.skis.mutation.InsertResult;
import io.skis.mutation.SnowflakeIdGenerator;
import io.skis.runtime.Skis;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class GeneratedIdConsumerTest {
  @Test
  void usesGeneratedPublicBindingsAndTheTypedInsertResultOutsideTheReactor() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:generated_consumer_" + UUID.randomUUID());
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE \"generated_ticket\" (\"id\" UUID PRIMARY KEY, \"name\" VARCHAR(100))");
      var generator = SnowflakeIdGenerator.ofNode(11);
      var executor = Skis.builder(source).dialect(H2Dialect.INSTANCE)
          .runtimeModels(List.of(GeneratedTicketRuntimeModel.MODEL))
          .snowflakeIdGenerator(generator).build();
      var input = new GeneratedTicket(null, "consumer");
      InsertResult<GeneratedTicket> result =
          executor.insertResult(GeneratedTicketTable.generatedTicketTable, input);
      UUID id = result.entity().id();
      assertNotNull(id);
      assertEquals(4, id.version());
      assertNull(input.id());
      assertEquals("consumer", executor.findById(GeneratedTicketTable.generatedTicketTable, id)
          .orElseThrow().name());
      assertTrue(generator.nextId() > 0);
    }
  }
}
