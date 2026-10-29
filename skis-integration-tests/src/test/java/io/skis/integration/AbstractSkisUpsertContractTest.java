package io.skis.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.*;
import io.skis.core.spi.*;
import io.skis.dialect.spi.*;
import io.skis.integration.ids.UuidStringIdBean;
import io.skis.integration.ids.skis.*;
import io.skis.integration.write.*;
import io.skis.integration.write.skis.*;
import io.skis.jdbc.JdbcExecutionException;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mutation.*;
import io.skis.query.*;
import io.skis.runtime.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** M18/M30-M35: actual PG conflict and H2 MERGE behavior, never cross-dialect emulation. */
abstract class AbstractSkisUpsertContractTest {
  static final WriteRowTable ROW = WriteRowTable.writeRowTable;
  static final MergeRowTable PLAIN = MergeRowTable.mergeRowTable;
  protected abstract DataSource createDataSource();
  protected abstract Dialect dialect();
  protected AutoCloseable synchronizeConcurrentInserts(Connection connection) throws Exception {
    return () -> {};
  }
  private boolean pg() { return dialect().capabilities().supports(DialectFeature.INSERT_ON_CONFLICT); }

  @Test void explicitColumnsKeepNullsVersionsAndActualRows() throws Exception {
    try (var f = new Fixture()) {
      assertEquals(0L, upsert(f, 1L, "first", null).version());
      assertEquals(new WriteRow(1L, "second", null, 1L), upsert(f, 1L, "second", null));
      assertEquals(new WriteRow(2L, "insert", null, 0L), upsert(f, 2L, "insert", 99L),
          "expected version does not require prior existence or initialize the inserted version");
      assertEquals(2L, upsert(f, 1L, "checked", 1L).version());
      assertThrows(OptimisticLockException.class, () -> upsert(f, 1L, "stale", 0L));
      assertEquals("checked", f.executor.findById(ROW, 1L).orElseThrow().name());
    }
  }

  private WriteRow upsert(Fixture f, long id, String name, Long expected) {
    return upsert(f.executor, id, name, expected);
  }

  private WriteRow upsert(MutationOperations operations, long id, String name, Long expected) {
    if (pg()) {
      var update = operations.insertInto(ROW).values(row -> row
          .value(ROW.id(), id)
          .value(ROW.name(), name)
          .value(ROW.note(), null)).onConflict(ROW.id()).doUpdate()
          .setExpression(ROW.name(), Sql.excluded(ROW.name())).set(ROW.note(), (String) null);
      if (expected != null) update = update.expectedVersion(expected.longValue());
      return update.returning().write().get();
    }
    var key = Sql.output(ROW.id(), "id");
    var text = Sql.output(ROW.name(), "name");
    var source = Sql.values(row -> row.value(key, id).value(text, name)).as("incoming");
    var update = operations.mergeInto(ROW).using(source).on(ROW.id().eq(source.column(key)))
        .whenMatched().thenUpdate().setExpression(ROW.name(), source.column(text)).set(ROW.note(), (String) null);
    if (expected != null) update = update.expectedVersion(expected.longValue());
    return update.whenNotMatched().thenInsert(row -> row
        .value(ROW.id(), source.column(key))
        .value(ROW.name(), source.column(text))).returning().write().get().getFirst();
  }

  @Test void supportsIgnoreAndKeepsNoResultDetached() throws Exception {
    try (var f = new Fixture()) {
      if (pg()) {
        var request = f.executor.insertInto(ROW).values(row -> row
            .value(ROW.id(), 1L)
            .value(ROW.name(), "one")).onConflict(ROW.id()).doNothing().returning(WriteReceiptPro.all());
        assertEquals(new WriteReceipt(1L, "one"), request.write().get());
        var skipped = request.write(); assertEquals(0, skipped.affectedRows()); assertTrue(skipped.isEmpty());
        int compiled = f.compilations.get(); f.executor.clearMutationPlanCache();
        assertTrue(request.write().isEmpty()); assertEquals(compiled + 1, f.compilations.get());
      } else {
        var id = Sql.output(ROW.id(), "id"); var name = Sql.output(ROW.name(), "name");
        var source = Sql.values(row -> row.value(id, 1L).value(name, "one")).as("incoming");
        var request = f.executor.mergeInto(ROW).using(source).on(ROW.id().eq(source.column(id)))
            .whenNotMatched().thenInsert(row -> row
                .value(ROW.id(), source.column(id))
                .value(ROW.name(), source.column(name)))
            .returning(WriteReceiptPro.all());
        assertEquals(List.of(new WriteReceipt(1L, "one")), request.write().get());
        var skipped = request.write(); assertEquals(0, skipped.affectedRows()); assertTrue(skipped.isEmpty());
        int compiled = f.compilations.get(); f.executor.clearMutationPlanCache();
        assertTrue(request.write().isEmpty()); assertEquals(compiled + 1, f.compilations.get());
      }
      assertEquals(0L, f.executor.findById(ROW, 1L).orElseThrow().version());
    }
  }

  @Test void nullableBusinessKeysKeepActualSqlNullMatchingSemantics() throws Exception {
    try (var f = new Fixture()) {
      if (pg()) {
        try (var sql = f.connection.createStatement()) { sql.execute("CREATE UNIQUE INDEX \"null_note\" ON \"merge_row\" (\"note\")"); }
        for (long id : new long[] {1L, 2L}) assertEquals(1, f.executor.insertInto(PLAIN)
            .values(row -> row.value(PLAIN.id(), id).value(PLAIN.name(), "nullable").value(PLAIN.note(), null))
            .onConflict(PLAIN.note()).doNothing().write());
        assertEquals(1, f.executor.insertInto(PLAIN).values(row -> row
            .value(PLAIN.id(), 3L)
            .value(PLAIN.name(), "first")
            .value(PLAIN.note(), "unique")).onConflict(PLAIN.note()).doNothing().write());
        assertEquals(3L, f.executor.insertInto(PLAIN).values(row -> row
            .value(PLAIN.id(), 4L)
            .value(PLAIN.name(), "changed")
            .value(PLAIN.note(), "unique")).onConflict(PLAIN.note()).doUpdate()
            .setExpression(PLAIN.name(), Sql.excluded(PLAIN.name())).returning().write().get().id());
      } else {
        var id = Sql.output(PLAIN.id(), "id"); var note = Sql.output(PLAIN.note(), "note");
        for (long candidate : new long[] {1L, 2L}) {
          var incoming = Sql.values(row -> row.value(id, candidate).value(note, null)).as("incoming");
          assertEquals(1, f.executor.mergeInto(PLAIN).using(incoming).on(PLAIN.note().eq(incoming.column(note)))
              .whenMatched().thenUpdate().set(PLAIN.name(), "matched")
              .whenNotMatched().thenInsert(row -> row
                  .value(PLAIN.id(), incoming.column(id))
                  .value(PLAIN.name(), Sql.value("new"))
                  .value(PLAIN.note(), incoming.column(note))).write());
        }
        assertEquals("new", f.executor.findById(PLAIN, 1L).orElseThrow().name());
        assertEquals("new", f.executor.findById(PLAIN, 2L).orElseThrow().name());
      }
    }
  }

  @Test void reusesBoundedPlansAcrossValuesRequestsAndSessionRollback() throws Exception {
    try (var f = new Fixture()) {
      upsert(f, 1L, "first", null); int compiled = f.compilations.get();
      upsert(f, 2L, "second", null); upsert(f, 1L, "third", null);
      try (var session = f.executor.beginTransaction()) {
        assertEquals(0L, upsert(session, 5L, "session", null).version());
        session.rollback();
      }
      assertEquals(compiled, f.compilations.get(), "ordinary values never change the shared plan");
      assertTrue(f.executor.findById(ROW, 5L).isEmpty());
      assertTrue(f.executor.mutationPlanCacheStatistics().hitCount() >= 2);
      f.connection.setAutoCommit(false);
      upsert(f, 3L, "rollback", null); f.connection.rollback();
      assertTrue(f.executor.findById(ROW, 3L).isEmpty()); assertFalse(f.connection.isClosed());
      f.connection.setAutoCommit(true);
      f.executor.clearMutationPlanCache(); upsert(f, 4L, "recompiled", null);
      assertTrue(f.compilations.get() > compiled);
    }
  }

  @Test void explicitRowsExceedEightColumnsThroughInsertAndNativeConflictForms() throws Exception {
    try (var f = new Fixture()) {
      try (var sql = f.connection.createStatement()) {
        sql.execute("CREATE TEMPORARY TABLE \"wide_write_row\" (\"id\" BIGINT PRIMARY KEY, "
            + "\"v1\" BIGINT, \"v2\" BIGINT, \"v3\" BIGINT, \"v4\" BIGINT, "
            + "\"v5\" BIGINT, \"v6\" BIGINT, \"v7\" BIGINT, \"v8\" BIGINT)");
      }
      var table = WideWriteRowTable.wideWriteRowTable;
      var request = f.executor.insertInto(table).values(row -> row
          .value(table.id(), 1L).value(table.v1(), 11L).value(table.v2(), 12L)
          .value(table.v3(), 13L).value(table.v4(), 14L).value(table.v5(), 15L)
          .value(table.v6(), 16L).value(table.v7(), 17L).value(table.v8(), null));
      var initial = new WideWriteRow(1L, 11L, 12L, 13L, 14L, 15L, 16L, 17L, null);
      assertEquals(initial, request.returning().write().get());
      if (pg()) {
        assertEquals(0, request.onConflict(table.id()).doNothing().write());
      } else {
        assertEquals(initial, f.executor.mergeInto(table).key(table.id()).values(row -> row
            .value(table.id(), 1L).value(table.v1(), 11L).value(table.v2(), 12L)
            .value(table.v3(), 13L).value(table.v4(), 14L).value(table.v5(), 15L)
            .value(table.v6(), 16L).value(table.v7(), 17L).value(table.v8(), null))
            .returning().write().get());
      }
      assertEquals(initial, f.executor.findById(table, 1L).orElseThrow());
    }
  }

  @Test void rowCallbacksCaptureOnceAndRejectInvalidOrExpiredCollectorsBeforeJdbc() throws Exception {
    try (var f = new Fixture()) {
      var retained = new AtomicReference<ValueRow>();
      var calls = new AtomicInteger();
      var input = f.executor.insertInto(PLAIN).values(row -> {
        calls.incrementAndGet();
        retained.set(row);
        row.value(PLAIN.id(), 1L).value(PLAIN.name(), "one").value(PLAIN.note(), null);
      });
      assertEquals(SkisErrorCode.MUTATION_PARAMETER_SHAPE, assertThrows(MutationException.class,
          () -> Objects.requireNonNull(retained.get()).value(PLAIN.note(), "late")).code());
      assertThrows(MutationException.class, () -> f.executor.insertInto(PLAIN).values(row -> {}));
      assertThrows(MutationException.class, () -> f.executor.insertInto(PLAIN)
          .values(row -> row.value(PLAIN.id(), 1L).value(PLAIN.id(), 2L)));
      assertThrows(MutationException.class, () -> f.executor.insertInto(PLAIN)
          .values(row -> row.value(PLAIN.as("foreign").id(), 1L)));
      Selectable<String> required = PLAIN.name();
      assertThrows(MutationException.class, () -> f.executor.insertInto(PLAIN)
          .values(row -> row.value(required, null)));
      var failure = new IllegalStateException("callback failure");
      assertSame(failure, assertThrows(IllegalStateException.class, () -> f.executor.insertInto(PLAIN)
          .values(row -> { retained.set(row); throw failure; })));
      assertThrows(MutationException.class, () -> Objects.requireNonNull(retained.get()).value(PLAIN.id(), 2L));

      var id = Sql.output(PLAIN.id(), "id");
      var source = Sql.values(row -> row.value(id, 2L));
      var branch = f.executor.mergeInto(PLAIN).using(source)
          .on(PLAIN.id().eq(source.column(id))).whenNotMatched();
      var expressions = new AtomicReference<ExpressionRow>();
      var merge = branch.thenInsert(row -> {
        expressions.set(row);
        row.value(PLAIN.id(), source.column(id)).value(PLAIN.name(), Sql.value("two"));
      });
      assertThrows(MutationException.class,
          () -> Objects.requireNonNull(expressions.get()).value(PLAIN.name(), Sql.value("late")));
      assertThrows(MutationException.class, () -> branch.thenInsert(row -> {}));
      assertThrows(MutationException.class, () -> branch.thenInsert(row -> row
          .value(PLAIN.id(), source.column(id)).value(PLAIN.id(), source.column(id))));
      assertSame(failure, assertThrows(IllegalStateException.class, () -> branch.thenInsert(row -> {
        expressions.set(row); throw failure;
      })));
      assertThrows(MutationException.class,
          () -> Objects.requireNonNull(expressions.get()).value(PLAIN.id(), source.column(id)));
      assertEquals(0, f.acquisitions.get());
      assertEquals(1, input.write());
      if (pg()) {
        var ignore = input.onConflict(PLAIN.id()).doNothing();
        assertEquals(0, ignore.write());
        assertTrue(ignore.returning().write().isEmpty());
      } else {
        assertEquals(1, merge.write());
        assertEquals("two", f.executor.findById(PLAIN, 2L).orElseThrow().name());
      }
      assertEquals(1, calls.get(), "execution never invokes the row callback again");
    }
  }

  @Test void warmedPlansStillRejectForeignOccurrencesBeforeJdbc() throws Exception {
    try (var f = new Fixture()) {
      if (pg()) {
        var valid = f.executor.insertInto(PLAIN).values(row -> row
            .value(PLAIN.id(), 1L)
            .value(PLAIN.name(), "one")).onConflict(PLAIN.id()).doUpdate()
            .setExpression(PLAIN.name(), Sql.excluded(PLAIN.name()))
            .where(PLAIN.id().eq(Sql.excluded(PLAIN.id())));
        assertEquals(1, valid.write());
        var returning = valid.returning();
        assertEquals("one", returning.write().get().name());
        int compiled = f.compilations.get();
        long hits = f.executor.mutationPlanCacheStatistics().hitCount();
        assertEquals(1, valid.write());
        assertEquals("one", returning.write().get().name());
        assertEquals(compiled, f.compilations.get(), "reused requests do not recompile");
        assertEquals(hits + 2, f.executor.mutationPlanCacheStatistics().hitCount());
        int acquisitions = f.acquisitions.get();
        var cached = f.executor.mutationPlanCacheStatistics();
        var foreign = PLAIN.as("other");
        for (int clause : new int[] {0, 1}) {
          var invalid = f.executor.insertInto(PLAIN).values(row -> row
              .value(PLAIN.id(), 1L)
              .value(PLAIN.name(), "poisoned")).onConflict(PLAIN.id()).doUpdate()
              .setExpression(PLAIN.name(), Sql.excluded(clause == 0 ? foreign.name() : PLAIN.name()))
              .where(PLAIN.id().eq(Sql.excluded(clause == 1 ? foreign.id() : PLAIN.id())));
          assertEquals(SkisErrorCode.MUTATION_PARAMETER_SHAPE,
              assertThrows(MutationException.class, invalid::write).code());
          assertEquals(SkisErrorCode.MUTATION_PARAMETER_SHAPE,
              assertThrows(MutationException.class, () -> invalid.returning().write()).code());
        }
        assertEquals(acquisitions, f.acquisitions.get(), "invalid requests never acquire JDBC");
        assertEquals(compiled, f.compilations.get());
        assertEquals(cached, f.executor.mutationPlanCacheStatistics(),
            "scope validation precedes even a matching cached plan");
      } else {
        var id = Sql.output(PLAIN.id(), "id");
        var name = Sql.output(PLAIN.name(), "name");
        var source = Sql.values(row -> row.value(id, 1L).value(name, "one")).as("incoming");
        var valid = f.executor.mergeInto(PLAIN).using(source)
            .on(PLAIN.id().eq(source.column(id)))
            .whenMatched().and(PLAIN.name().eq(source.column(name))).thenUpdate()
            .setExpression(PLAIN.note(), source.column(name))
            .whenNotMatched().thenInsert(row -> row
                .value(PLAIN.id(), source.column(id))
                .value(PLAIN.name(), source.column(name)));
        assertEquals(1, valid.write());
        var returning = valid.returning();
        assertEquals("one", returning.write().get().getFirst().name());
        int compiled = f.compilations.get();
        long hits = f.executor.mutationPlanCacheStatistics().hitCount();
        assertEquals(1, valid.write());
        assertEquals("one", returning.write().get().getFirst().name());
        assertEquals(compiled, f.compilations.get(), "reused requests do not recompile");
        assertEquals(hits + 2, f.executor.mutationPlanCacheStatistics().hitCount());
        int acquisitions = f.acquisitions.get();
        var cached = f.executor.mutationPlanCacheStatistics();
        var foreign = Sql.values(row -> row.value(id, 99L).value(name, "poisoned")).as("incoming");
        for (int clause : new int[] {0, 1, 2, 3}) {
          var invalid = f.executor.mergeInto(PLAIN).using(source)
              .on(PLAIN.id().eq((clause == 0 ? foreign : source).column(id)))
              .whenMatched().and(PLAIN.name().eq((clause == 1 ? foreign : source).column(name)))
              .thenUpdate().setExpression(PLAIN.note(), (clause == 2 ? foreign : source).column(name))
              .whenNotMatched().thenInsert(row -> row
                  .value(PLAIN.id(), (clause == 3 ? foreign : source).column(id))
                  .value(PLAIN.name(), source.column(name)));
          assertEquals(SkisErrorCode.MUTATION_PARAMETER_SHAPE,
              assertThrows(MutationException.class, invalid::write).code());
          assertEquals(SkisErrorCode.MUTATION_PARAMETER_SHAPE,
              assertThrows(MutationException.class, () -> invalid.returning().write()).code());
        }
        assertEquals(acquisitions, f.acquisitions.get(), "invalid requests never acquire JDBC");
        assertEquals(compiled, f.compilations.get());
        assertEquals(cached, f.executor.mutationPlanCacheStatistics(),
            "same-alias foreign sources cannot impersonate the USING occurrence");
      }
      assertEquals("one", f.executor.findById(PLAIN, 1L).orElseThrow().name());
    }
  }

  @Test void validatesDialectOccurrenceKeyAndAmbiguousOptimisticRequestsBeforeJdbc() throws Exception {
    try (var f = new Fixture()) {
      if (pg()) {
        assertThrows(RuntimeException.class, () -> f.executor.mergeInto(PLAIN).key(PLAIN.id()).values(row -> row
            .value(PLAIN.id(), 1L)
            .value(PLAIN.name(), "one")).write());
        assertThrows(MutationException.class, () -> f.executor.insertInto(ROW).values(row -> row
            .value(ROW.id(), 1L)
            .value(ROW.name(), "one")).onConflict(ROW.id()).doUpdate().set(ROW.name(), "other")
            .expectedVersion(0L).where(ROW.note().isNull()).write());
        assertThrows(RuntimeException.class, () -> f.executor.insertInto(ROW).values(row -> row
            .value(ROW.id(), 1L)
            .value(ROW.name(), "one")).onConflict(ROW.id()).doUpdate()
            .setExpression(ROW.name(), Sql.excluded(ROW.as("other").name())).write());
      } else {
        assertThrows(RuntimeException.class, () -> f.executor.insertInto(PLAIN).values(row -> row
            .value(PLAIN.id(), 1L)
            .value(PLAIN.name(), "one")).onConflict(PLAIN.id()).doNothing().write());
        assertThrows(MutationException.class, () -> f.executor.mergeInto(ROW).key(ROW.id()).values(row -> row
            .value(ROW.id(), 1L)
            .value(ROW.name(), "one")));
        assertThrows(MutationException.class, () -> f.executor.mergeInto(PLAIN).key(PLAIN.name()).values(row -> row
            .value(PLAIN.id(), 1L)
            .value(PLAIN.name(), "one")));
        var id = Sql.output(ROW.id(), "id"); var source = Sql.values(row -> row.value(id, 1L)).as("incoming");
        assertThrows(MutationException.class, () -> f.executor.mergeInto(ROW).using(source)
            .on(ROW.id().eq(source.column(id))).whenMatched().and(ROW.note().isNull()).thenUpdate()
            .set(ROW.name(), "one").expectedVersion(0L).whenNotMatched().thenInsert(row -> row
                .value(ROW.id(), source.column(id))
                .value(ROW.name(), Sql.value("one"))).write());
      }
      assertThrows(RuntimeException.class, () -> f.executor.update(ROW)
          .setExpression(ROW.name(), Sql.excluded(ROW.name())).allowAllRows().write());
      assertEquals(0, f.acquisitions.get());
    }
  }

  @Test void keyAndMergeBranchesHaveIndependentColumnSetsAndCardinality() throws Exception {
    try (var f = new Fixture()) {
      if (pg()) {
        assertEquals(1, f.executor.insertInto(PLAIN).values(row -> row
            .value(PLAIN.id(), 1L)
            .value(PLAIN.name(), "one")
            .value(PLAIN.note(), "keep")).onConflict(PLAIN.id()).doNothing().write());
        assertEquals(new MergeRow(1L, "changed", "keep"), f.executor.insertInto(PLAIN)
            .values(row -> row
                .value(PLAIN.id(), 1L)
                .value(PLAIN.name(), "changed")).onConflict(PLAIN.id()).doUpdate()
            .setExpression(PLAIN.name(), Sql.excluded(PLAIN.name())).returning().write().get());
      } else {
        var key = f.executor.mergeInto(PLAIN).key(PLAIN.id()).values(row -> row
            .value(PLAIN.id(), 1L)
            .value(PLAIN.name(), "one")
            .value(PLAIN.note(), "keep")).returning();
        assertEquals(new MergeRow(1L, "one", "keep"), key.write().get());
        assertEquals(1, f.executor.mergeInto(PLAIN).key(PLAIN.id()).values(row -> row
            .value(PLAIN.id(), 1L)
            .value(PLAIN.name(), "changed")).write());
        assertEquals("keep", f.executor.findById(PLAIN, 1L).orElseThrow().note());
        f.executor.insertInto(PLAIN).value(new MergeRow(2L, "changed", "two")).write();
        var name = Sql.output(PLAIN.name(), "name"); var source = Sql.values(row -> row
            .value(name, "changed")).as("incoming");
        var rows = f.executor.mergeInto(PLAIN).using(source).on(PLAIN.name().eq(source.column(name)))
            .whenMatched().thenUpdate().set(PLAIN.note(), "updated").returning().write();
        assertEquals(2, rows.affectedRows()); assertEquals(2, rows.get().size());
        assertThrows(UnsupportedOperationException.class, () -> rows.get().clear());
      }
    }
  }

  @Test void generatedConflictKeyIsActualAndIgnoredCandidateNeverBackfills() throws Exception {
    try (var f = new Fixture()) {
      if (pg()) {
        var table = UuidStringIdBeanTable.uuidStringIdBeanTable;
        var first = new UuidStringIdBean();
        f.executor.insertInto(table).value(first).onConflict(table.name()).doNothing().write();
        String actual = first.getId(); assertNotNull(actual);
        var ignored = new UuidStringIdBean();
        assertEquals(0, f.executor.insertInto(table).value(ignored).onConflict(table.name()).doNothing().write());
        assertNull(ignored.getId()); assertEquals(0, ignored.writes);
        var updated = new UuidStringIdBean();
        var result = f.executor.insertInto(table).value(updated).onConflict(table.name()).doUpdate()
            .setExpression(table.name(), Sql.excluded(table.name()))
            .returning(WriteViewPro.of(table.name(), table.name())).write();
        assertEquals(new WriteView("new", "new"), result.get());
        assertEquals(actual, updated.getId()); assertEquals(1, updated.writes);
      } else {
        var id = Sql.output(ROW.id(), "id"); var expected = Sql.output(Sql.value(0L), "expected");
        var source = Sql.values(row -> row.value(id, 1L).value(expected, 0L)).as("incoming");
        f.executor.insertInto(ROW).value(new WriteRow(1L, "one", null, null)).write();
        var request = f.executor.mergeInto(ROW).using(source).on(ROW.id().eq(source.column(id)))
            .whenMatched().thenUpdate().set(ROW.name(), "checked")
            .expectedVersionExpression(source.column(expected)).whenNotMatched().thenInsert(row -> row
                .value(ROW.id(), source.column(id))
                .value(ROW.name(), Sql.value("insert")));
        assertEquals(1, request.write());
        assertThrows(OptimisticLockException.class, request::write);
      }
    }
  }

  @Test void concurrentUniqueConflictsKeepActualPgAndH2OutcomesWithoutRetries() throws Exception {
    DataSource source = createDataSource();
    String schema = "race_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection control = source.getConnection()) {
      try (var sql = control.createStatement()) { sql.execute("CREATE SCHEMA \"" + schema + "\""); }
      try {
        control.setSchema(schema);
        try (var sql = control.createStatement()) {
          sql.execute("CREATE TABLE \"merge_row\" (\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(100) NOT NULL UNIQUE, \"note\" VARCHAR(100))");
        }
        var statements = new AtomicInteger();
        var executor = Skis.builder(source).dialect(dialect()).runtimeModels(List.of(MergeRowRuntimeModel.MODEL))
            .connectionProvider(new ConnectionProvider() {
              @Override public Connection acquire(ExecutionContext context) throws SQLException {
                Connection connection = source.getConnection();
                try { connection.setSchema(schema); return connection; }
                catch (SQLException failure) { connection.close(); throw failure; }
              }
              @Override public void release(Connection connection, ExecutionContext context) throws SQLException { connection.close(); }
            }).listener(new ExecutionListener() {
              @Override public void statementStarted(StatementStartedEvent event) { statements.incrementAndGet(); }
            }).build();
        var barrier = new CyclicBarrier(2);
        try (var insertGate = synchronizeConcurrentInserts(control);
            var workers = Executors.newFixedThreadPool(2)) {
          List<Future<MergeRow>> futures = new ArrayList<>();
          for (long id = 1; id <= 2; id++) {
            final long candidate = id;
            futures.add(workers.submit(() -> {
              barrier.await(15, TimeUnit.SECONDS);
              if (pg()) return executor.insertInto(PLAIN).values(row -> row
                  .value(PLAIN.id(), candidate)
                  .value(PLAIN.name(), "shared")).onConflict(PLAIN.name()).doUpdate()
                  .setExpression(PLAIN.name(), Sql.excluded(PLAIN.name())).returning().write().get();
              var key = Sql.output(PLAIN.id(), "id"); var name = Sql.output(PLAIN.name(), "name");
              var incoming = Sql.values(row -> row.value(key, candidate).value(name, "shared")).as("incoming");
              return executor.mergeInto(PLAIN).using(incoming).on(PLAIN.name().eq(incoming.column(name)))
                  .whenMatched().thenUpdate().set(PLAIN.note(), "matched")
                  .whenNotMatched().thenInsert(row -> row
                      .value(PLAIN.id(), incoming.column(key))
                      .value(PLAIN.name(), incoming.column(name)))
                  .returning().write().get().getFirst();
            }));
          }
          List<MergeRow> returned = new ArrayList<>();
          int failed = 0;
          for (var future : futures) {
            try { returned.add(future.get(30, TimeUnit.SECONDS)); }
            catch (ExecutionException failure) {
              if (pg()) throw failure;
              var jdbcFailure = assertInstanceOf(JdbcExecutionException.class, failure.getCause());
              var driverFailure = assertInstanceOf(SQLException.class, jdbcFailure.getCause());
              assertEquals("23505", driverFailure.getSQLState());
              assertEquals(SqlExceptionCategory.DUPLICATE_KEY, jdbcFailure.category());
              assertEquals(SkisErrorCode.DB_DUPLICATE_KEY, jdbcFailure.code());
              failed++;
            }
          }
          assertEquals(pg() ? 0 : 1, failed);
          assertEquals(pg() ? 2 : 1, returned.size());
          if (pg()) assertEquals(returned.getFirst().id(), returned.getLast().id());
          assertEquals(2, statements.get(), "each request executes exactly once, including failed H2 races");
          try (var sql = control.createStatement();
              var rows = sql.executeQuery("SELECT \"id\", \"name\", \"note\" FROM \"merge_row\"")) {
            assertTrue(rows.next());
            assertEquals(returned.getFirst().id().longValue(), rows.getLong(1));
            assertEquals("shared", rows.getString(2));
            assertNull(rows.getString(3), "the failed H2 insert must not become a matched update");
            assertFalse(rows.next());
          }
        }
      } finally {
        try (var sql = control.createStatement()) { sql.execute("DROP SCHEMA \"" + schema + "\" CASCADE"); }
      }
    }
  }

  @Test void inheritedVersionAndResourceFailureNeverSilentlyBackfill() throws Exception {
    try (var f = new Fixture()) {
      if (pg()) {
        f.executor.insertInto(ROW).value(new WriteRow(1L, "one", null, null)).write();
        var source = new WriteRow(1L, "two", null, 0L);
        var update = f.executor.insertInto(ROW).value(source).onConflict(ROW.id()).doUpdate()
            .setExpression(ROW.name(), Sql.excluded(ROW.name()));
        assertEquals(1L, update.returning().write().get().version());
        assertThrows(OptimisticLockException.class, update::write);
        assertEquals(2L, update.expectedVersion(1L).returning().write().get().version());
        assertEquals(0L, source.version());
        var table = UuidStringIdBeanTable.uuidStringIdBeanTable;
        var bean = new UuidStringIdBean(); f.failRelease = true;
        assertThrows(JdbcExecutionException.class,
            () -> f.executor.insertInto(table).value(bean).onConflict(table.name()).doNothing().write());
        assertNull(bean.getId()); assertEquals(0, bean.writes);
        f.failRelease = false;
        assertEquals(1, f.executor.selectFrom(table).fetchList().size(), "release failure does not invent rollback or retry");
      } else {
        f.executor.insertInto(PLAIN).value(new MergeRow(1L, "one", null)).write();
        f.failRelease = true;
        assertThrows(JdbcExecutionException.class, () -> f.executor.mergeInto(PLAIN)
            .key(PLAIN.id()).values(row -> row
                .value(PLAIN.id(), 1L)
                .value(PLAIN.name(), "changed")).returning().write());
        f.failRelease = false;
        assertEquals("changed", f.executor.findById(PLAIN, 1L).orElseThrow().name());
      }
    }
  }

  final class Fixture implements AutoCloseable {
    final Connection connection;
    final SkisExecutor executor;
    final AtomicInteger acquisitions = new AtomicInteger(), compilations = new AtomicInteger();
    boolean failRelease;
    Fixture() throws Exception {
      DataSource source = createDataSource(); connection = source.getConnection();
      try (var sql = connection.createStatement()) {
        sql.execute("CREATE TEMPORARY TABLE \"write_row\" (\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(100) NOT NULL, \"note\" VARCHAR(100), \"version\" BIGINT NOT NULL)");
        sql.execute("CREATE TEMPORARY TABLE \"merge_row\" (\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(100) NOT NULL, \"note\" VARCHAR(100))");
        sql.execute("CREATE TEMPORARY TABLE \"uuidstringidbean\" (\"Generated Key\" VARCHAR(36) PRIMARY KEY, \"name\" VARCHAR(100) NOT NULL UNIQUE)");
      }
      executor = Skis.builder(source).dialect(dialect()).runtimeModels(List.of(
          WriteRowRuntimeModel.MODEL, MergeRowRuntimeModel.MODEL, UuidStringIdBeanRuntimeModel.MODEL,
          WideWriteRowRuntimeModel.MODEL))
          .connectionProvider(new ConnectionProvider() {
            @Override public Connection acquire(ExecutionContext context) { acquisitions.incrementAndGet(); return connection; }
            @Override public void release(Connection released, ExecutionContext context) throws SQLException {
              assertSame(connection, released);
              if (failRelease) throw new SQLException("release failed", "08006");
            }
          }).listener(new ExecutionListener() {
            @Override public void compilationCompleted(CompilationCompletedEvent event) { compilations.incrementAndGet(); }
          }).build();
    }
    @Override public void close() throws Exception { connection.close(); }
  }
}
