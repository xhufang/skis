package io.skis.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.dialect.h2.H2Dialect;
import io.skis.dialect.spi.Dialect;
import io.skis.query.Sql;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.h2.api.Trigger;
import org.h2.jdbcx.JdbcDataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

public class SkisUpsertH2ContractTest extends AbstractSkisUpsertContractTest {
  @Override protected DataSource createDataSource() {
    var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:upsert_" + UUID.randomUUID()); return source;
  }
  @Override protected Dialect dialect() { return H2Dialect.INSTANCE; }

  @Override protected AutoCloseable synchronizeConcurrentInserts(Connection connection) throws SQLException {
    String schema = connection.getSchema();
    InsertBarrier.GATES.put(schema, new CyclicBarrier(2));
    try (var sql = connection.createStatement()) {
      sql.execute("CREATE TRIGGER \"synchronize_insert\" BEFORE INSERT ON \"merge_row\" FOR EACH ROW CALL '"
          + InsertBarrier.class.getName() + "'");
    } catch (SQLException | RuntimeException failure) {
      InsertBarrier.GATES.remove(schema);
      throw failure;
    }
    return () -> InsertBarrier.GATES.remove(schema);
  }

  @Test void mergeInsertKeepsIntegerDivisionForPositiveAndNegativeParameters() throws Exception {
    try (var fixture = new Fixture()) {
      var id = Sql.output(PLAIN.id(), "id");
      for (long numerator : new long[] {5L, -5L}) {
        long expected = numerator / 2L;
        var source = Sql.values(row -> row.value(id, expected)).as("incoming");
        var request = fixture.executor.mergeInto(PLAIN).using(source)
            .on(PLAIN.id().eq(source.column(id))).whenNotMatched().thenInsert(row -> row
                .value(PLAIN.id(), Sql.divide(Sql.value(numerator), Sql.value(2L)))
                .value(PLAIN.name(), Sql.value("division")))
            .returning();
        var inserted = request.write();
        assertEquals(1, inserted.affectedRows());
        assertEquals(expected, inserted.get().getFirst().id());
        assertTrue(request.write().isEmpty());
        assertTrue(fixture.executor.findById(PLAIN, expected).isPresent());
      }
      assertTrue(fixture.executor.findById(PLAIN, 3L).isEmpty());
      assertTrue(fixture.executor.findById(PLAIN, -3L).isEmpty());
    }
  }

  /** Test-only gate after both MERGEs have selected their NOT MATCHED branch. */
  public static final class InsertBarrier implements Trigger {
    private static final Map<String, CyclicBarrier> GATES = new ConcurrentHashMap<>();
    private @Nullable CyclicBarrier gate;

    @Override public void init(Connection connection, String schemaName, String triggerName,
        String tableName, boolean before, int type) {
      gate = Objects.requireNonNull(GATES.get(schemaName));
    }

    @Override public void fire(Connection connection, @Nullable Object @Nullable [] oldRow,
        @Nullable Object @Nullable [] newRow) throws SQLException {
      try {
        Objects.requireNonNull(gate).await(15, TimeUnit.SECONDS);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new SQLException("concurrent INSERT gate interrupted", "HYT00", failure);
      } catch (BrokenBarrierException | TimeoutException failure) {
        throw new SQLException("concurrent INSERT gate did not receive both writers", "HYT00", failure);
      }
    }
  }
}
