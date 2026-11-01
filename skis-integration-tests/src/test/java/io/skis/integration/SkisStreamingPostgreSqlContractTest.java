package io.skis.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.ExecutionOptions;
import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.spi.ExecutionContext;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.query.QueryCursor;
import io.skis.query.Sql;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import io.skis.testmodel.pet.skis.PetTable;
import io.skis.testsupport.PostgresContract;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.PooledConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGConnectionPoolDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.jdbc.PreferQueryMode;

@PostgresContract
class SkisStreamingPostgreSqlContractTest {
  private final PGSimpleDataSource source;

  SkisStreamingPostgreSqlContractTest(PGSimpleDataSource source) {
    this.source = source;
  }

  @BeforeEach
  void fixture() throws Exception {
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS shelter");
      statement.execute("DROP VIEW IF EXISTS shelter.pet");
      statement.execute("DROP SEQUENCE IF EXISTS shelter.cursor_progress");
      statement.execute("CREATE SEQUENCE shelter.cursor_progress");
      // nextval is volatile and observable from another statement on the same connection. No sort,
      // aggregate, or materializing operator is allowed in the demand-reading proof below.
      statement.execute("CREATE VIEW shelter.pet AS SELECT "
          + "nextval('shelter.cursor_progress') AS id, 'row'::varchar AS pet_name, "
          + "1.00::numeric AS weight, false AS adopted, 0::bigint AS version "
          + "FROM generate_series(1, 1000)");
    }
  }

  @Test
  void defaultFetchReadsOnlyOneServerBatchAndRefillsOnDemand() throws Exception {
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      SkisExecutor executor = retainedConnection(connection);
      try (var cursor = executor.select(PetTable.petTable.id()).from(PetTable.petTable).cursor()) {
        assertEquals(256, progress(connection));
        for (int index = 1; index <= 256; index++) {
          assertTrue(cursor.advance());
          assertEquals((long) index, cursor.current());
        }
        assertEquals(256, progress(connection));
        assertTrue(cursor.advance());
        assertEquals(257L, cursor.current());
        assertEquals(512, progress(connection));
        int total = 257;
        while (cursor.advance()) total++;
        assertEquals(1000, total);
        assertEquals(1000, progress(connection));
        assertTrue(cursor.isClosed());
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  void requestAndDirectStreamsHonorExplicitOptionsAndShortCircuitOwnership() throws Exception {
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      SkisExecutor executor = retainedConnection(connection);
      var request = Sql.select(PetTable.petTable.id()).from(PetTable.petTable).stream()
          .withOptions(ExecutionOptions.builder().fetchSize(3).build());
      try (var rows = executor.run(request)) {
        assertEquals(3, progress(connection));
        assertEquals(1L, rows.findFirst().orElseThrow());
        assertEquals(3, progress(connection));
      }
      var options = ExecutionOptions.builder().fetchSize(2).build();
      try (var rows = executor.select(PetTable.petTable.id()).from(PetTable.petTable)
          .withOptions(options).stream()) {
        assertEquals(5, progress(connection));
        assertEquals(4L, rows.findFirst().orElseThrow());
      }
      assertFalse(connection.getAutoCommit());
      connection.rollback();
    }
  }

  @Test
  void rejectsAutoCommitAndZeroFetchSizeWhileMaterializedQueriesRemainUsable() {
    SkisExecutor executor = Skis.create(source, PostgreSqlDialect.INSTANCE);
    var query = executor.select(PetTable.petTable.id()).from(PetTable.petTable);
    assertEquals(SkisErrorCode.QUERY_STREAMING_PRECONDITION,
        assertThrows(QueryValidationException.class, query::cursor).code());
    try (var session = executor.beginTransaction()) {
      var zero = ExecutionOptions.builder().fetchSize(0).build();
      assertEquals(SkisErrorCode.QUERY_STREAMING_PRECONDITION,
          assertThrows(QueryValidationException.class, () -> session.run(
              Sql.select(PetTable.petTable.id()).from(PetTable.petTable).stream().withOptions(zero))).code());
      assertEquals(1000, session.select(PetTable.petTable.id()).from(PetTable.petTable)
          .withOptions(zero).fetchList().size());
    }
  }

  @Test
  void rejectsSimpleProtocolBeforeItCanBufferTheResult() throws Exception {
    PGSimpleDataSource simple = new PGSimpleDataSource();
    simple.setURL(source.getURL());
    simple.setUser(source.getUser());
    simple.setPassword(source.getPassword());
    simple.setPreferQueryMode(PreferQueryMode.SIMPLE);
    try (var session = Skis.create(simple, PostgreSqlDialect.INSTANCE).beginTransaction()) {
      assertEquals(SkisErrorCode.QUERY_STREAMING_PRECONDITION,
          assertThrows(QueryValidationException.class,
              () -> session.selectFrom(PetTable.petTable).cursor()).code());
      assertTrue(session.active());
      assertEquals(1000, session.selectFrom(PetTable.petTable).fetchList().size());
    }
    try (var connection = source.getConnection()) {
      // Only the explicitly materialized query above evaluated nextval.
      assertEquals(1000, progress(connection));
    }
  }

  @Test
  void sessionCompletionClosesResourcesBeforeLogicalPoolReturnAndRestoresState() throws Exception {
    PGConnectionPoolDataSource poolSource = new PGConnectionPoolDataSource();
    poolSource.setURL(source.getURL());
    poolSource.setUser(source.getUser());
    poolSource.setPassword(source.getPassword());
    PooledConnection pooled = poolSource.getPooledConnection();
    try {
      AtomicInteger releases = new AtomicInteger();
      AtomicReference<QueryCursor<Long>> open = new AtomicReference<>();
      ConnectionProvider provider = new ConnectionProvider() {
        @Override public Connection acquire(ExecutionContext context) throws SQLException {
          return pooled.getConnection();
        }
        @Override public void release(Connection connection, ExecutionContext context) throws SQLException {
          QueryCursor<Long> cursor = open.get();
          assertNotNull(cursor);
          assertTrue(cursor.isClosed());
          assertTrue(connection.getAutoCommit());
          releases.incrementAndGet();
          connection.close();
        }
      };
      SkisExecutor executor = Skis.builder(source).connectionProvider(provider)
          .dialect(PostgreSqlDialect.INSTANCE).build();
      for (boolean commit : List.of(true, false)) {
        try (var session = executor.beginTransaction()) {
          open.set(session.run(Sql.select(PetTable.petTable.id()).from(PetTable.petTable).cursor()));
          assertTrue(open.get().advance());
          if (commit) session.commit(); else session.rollback();
          assertTrue(open.get().isClosed());
          assertFalse(open.get().advance());
        }
        try (Connection reused = pooled.getConnection(); var statement = reused.createStatement()) {
          assertTrue(reused.getAutoCommit());
          try (var result = statement.executeQuery("SELECT 1")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
          }
        }
      }
      assertEquals(2, releases.get());
    } finally {
      pooled.close();
    }
  }

  private SkisExecutor retainedConnection(Connection connection) {
    return Skis.builder(source).dialect(PostgreSqlDialect.INSTANCE)
        .connectionProvider(new ConnectionProvider() {
          @Override public Connection acquire(ExecutionContext context) { return connection; }
          @Override public void release(Connection released, ExecutionContext context) {
            assertSame(connection, released);
          }
        }).build();
  }

  private static long progress(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT last_value FROM shelter.cursor_progress")) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }
}
