package io.skis.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.*;
import io.skis.core.spi.*;
import io.skis.dialect.spi.Dialect;
import io.skis.integration.ids.UuidStringIdBean;
import io.skis.integration.ids.skis.UuidStringIdBeanRuntimeModel;
import io.skis.integration.ids.skis.UuidStringIdBeanTable;
import io.skis.integration.write.*;
import io.skis.integration.write.skis.*;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mutation.*;
import io.skis.query.Sql;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** M17/M22-M29: identical generated mappings and assertions on both real drivers. */
abstract class AbstractSkisReturningContractTest {
  private static final WriteRowTable ROW = WriteRowTable.writeRowTable;
  protected abstract DataSource createDataSource();
  protected abstract Dialect dialect();

  @Test
  void insertUpdateDeleteReturnFullRowsAndBothProjectionKinds() throws Exception {
    try (var f = new Fixture()) {
      var full = f.executor.insertInto(ROW).value(new WriteRow(1L, "one", null, null)).returning().write();
      assertEquals(new WriteRow(1L, "one", null, 0L), full.get());
      assertEquals(1, full.affectedRows());
      var reusable = f.executor.insertInto(ROW).value(new WriteRow(2L, "two", "note", null))
          .returning(WriteViewPro.of(ROW.name(), ROW.note())).write();
      assertEquals(new WriteView("two", "note"), reusable.get());
      var special = f.executor.insertInto(ROW).value(new WriteRow(3L, "three", null, null))
          .returning(WriteReceiptPro.all()).write();
      assertEquals(new WriteReceipt(3L, "three"), special.get());

      var updated = f.executor.update(ROW).set(ROW.name(), "changed")
          .where(ROW.id().in(List.of(1L, 2L))).returning().write();
      assertEquals(2, updated.affectedRows());
      assertEquals(List.of(1L, 2L), updated.get().stream().map(WriteRow::id).sorted().toList());
      assertTrue(updated.get().stream().allMatch(row -> row.version() == 1L));
      assertThrows(UnsupportedOperationException.class, () -> updated.get().clear());
      assertEquals(new WriteView("changed", null), f.executor.update(ROW).set(ROW.note(), (String) null)
          .where(ROW.id().eq(1L)).returning(WriteViewPro.of(ROW.name(), ROW.note())).write().get().getFirst());
      assertEquals(new WriteReceipt(3L, "three"), f.executor.update(ROW).set(ROW.note(), "new")
          .where(ROW.id().eq(3L)).returning(WriteReceiptPro.all()).write().get().getFirst());

      var deleted = f.executor.deleteFrom(ROW).where(ROW.id().eq(1L)).returning().write();
      assertEquals(new WriteRow(1L, "changed", null, 2L), deleted.get().getFirst());
      assertEquals(new WriteView("changed", "note"), f.executor.deleteFrom(ROW).where(ROW.id().eq(2L))
          .returning(WriteViewPro.of(ROW.name(), ROW.note())).write().get().getFirst());
      assertEquals(new WriteReceipt(3L, "three"), f.executor.deleteFrom(ROW).where(ROW.id().eq(3L))
          .returning(WriteReceiptPro.all()).write().get().getFirst());
      assertTrue(f.executor.deleteFrom(ROW).where(ROW.id().eq(1L)).returning().write().isEmpty());
      var empty = f.executor.update(ROW).set(ROW.name(), "absent").where(ROW.id().eq(99L)).returning().write();
      assertEquals(0, empty.affectedRows());
      assertTrue(empty.isEmpty());
      int statements = f.statements.get();
      full.map(WriteRow::name);
      full.toOptional();
      full.stream().count();
      empty.orElse(List.of());
      assertEquals(statements, f.statements.get(), "Written access is detached");
    }
  }

  @Test
  void validatesSafetyTypesOccurrencesAndBudgetsBeforeJdbc() throws Exception {
    try (var f = new Fixture()) {
      assertThrows(UnsafeMutationException.class, () -> f.executor.deleteFrom(ROW).write());
      assertThrows(UnsafeMutationException.class, () -> f.executor.update(ROW).set(ROW.name(), "all").write());
      assertThrows(MutationException.class, () -> f.executor.deleteFrom(ROW).and(ROW.id().eq(1L)));
      assertThrows(MutationException.class, () -> f.executor.deleteFrom(ROW).where(ROW.id().eq(1L)).where(ROW.id().eq(2L)));
      assertThrows(MutationException.class, () -> f.executor.update(ROW).set(ROW.id(), 9L));
      assertThrows(MutationException.class, () -> f.executor.update(ROW).set(ROW.version(), 9L));
      assertThrows(MutationException.class, () -> f.executor.update(ROW).set(ROW.name(), "a").set(ROW.name(), "b"));
      assertThrows(RuntimeException.class, () -> f.executor.update(ROW).set(ROW.name(), (String) null).allowAllRows().write());
      assertMutationShapeFailure(() -> f.executor.update(ROW)
          .setExpression(ROW.name(), ROW.note()).allowAllRows().write());
      assertMutationShapeFailure(() -> f.executor.update(ROW)
          .setExpression(ROW.name(), ROW.as("other").name()).allowAllRows().write());
      assertMutationShapeFailure(() -> f.executor.deleteFrom(ROW)
          .where(ROW.as("other").id().eq(1L)).write());
      assertMutationShapeFailure(() -> f.executor.update(ROW).set(ROW.note(), "unused")
          .where(ROW.as("other").id().eq(1L)).returning().write());
      assertMutationShapeFailure(() -> f.executor.update(ROW)
          .setExpression(ROW.name(), ROW.note()).allowAllRows().returning().write());
      assertMutationShapeFailure(() -> f.executor.deleteFrom(ROW)
          .where(ROW.as("other").id().eq(1L)).returning().write());
      assertThrows(RuntimeException.class, () -> f.executor.insertInto(ROW).value(new WriteRow(1L, "one", null, null))
          .returning(WriteViewPro.of(ROW.as("other").name(), ROW.note())));
      var ids = new ArrayList<Long>();
      for (long id = 0; id < 65536; id++) { ids.add(id); }
      assertThrows(RuntimeException.class, () -> f.executor.deleteFrom(ROW).where(ROW.id().in(ids)).write());
      assertEquals(0, f.acquisitions.get());
      assertEquals(0, f.statements.get());
    }
  }

  private static void assertMutationShapeFailure(org.junit.jupiter.api.function.Executable action) {
    var failure = assertThrows(MutationException.class, action);
    assertEquals(SkisErrorCode.MUTATION_PARAMETER_SHAPE, failure.code());
    assertEquals(SkisDiagnosticStage.COMPILATION, failure.diagnostics().stage());
    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
  }

  @Test
  void typesParameterOnlyExpressionsForCountAndReturningWrites() throws Exception {
    try (var f = new Fixture()) {
      f.executor.insertInto(ROW).value(new WriteRow(1L, "one", null, null)).write();
      assertEquals(1, f.executor.update(ROW)
          .setExpression(ROW.note(), Sql.coalesce(Sql.value(String.class, null), Sql.value("count")))
          .where(ROW.id().eq(1L)).and(Sql.value(String.class, null).isNull()).write());
      assertEquals("count", f.executor.findById(ROW, 1L).orElseThrow().note());
      var returned = f.executor.update(ROW)
          .setExpression(ROW.note(), Sql.coalesce(Sql.value(String.class, null), Sql.value("returned")))
          .where(ROW.id().eq(1L)).and(Sql.value(String.class, null).isNull()).returning().write();
      assertEquals("returned", returned.get().getFirst().note());
      assertEquals(2L, returned.get().getFirst().version());
      assertEquals(0, f.executor.deleteFrom(ROW).where(Sql.value("keep").isNull()).write());
      var deleted = f.executor.deleteFrom(ROW)
          .where(Sql.coalesce(Sql.value(String.class, null), Sql.value("returned")).eq(ROW.note()))
          .returning().write();
      assertEquals(1, deleted.affectedRows());
      assertEquals("returned", deleted.get().getFirst().note());
    }
  }

  @Test
  void conditionsUseOneOperationAndVersionAdvanceWithoutImplicitOptimisticChecks() throws Exception {
    try (var f = new Fixture()) {
      for (long id = 1; id <= 3; id++) {
        f.executor.insertInto(ROW).value(new WriteRow(id, "row-" + id, null, null)).write();
      }
      int started = f.statements.get();
      int rows = f.executor.update(ROW).setExpression(ROW.note(), Sql.concat(ROW.name(), Sql.value("!")))
          .where(ROW.id().eq(1L)).or(ROW.id().eq(2L)).and(ROW.name().eq("row-2")).write();
      assertEquals(1, rows, "where/and/or associate from left to right");
      assertEquals(started + 1, f.statements.get());
      assertEquals("row-2!", f.executor.findById(ROW, 2L).orElseThrow().note());
      assertEquals(1, f.executor.update(ROW).set(ROW.note(), "grouped")
          .where(ROW.id().eq(1L).or(ROW.id().eq(2L).and(ROW.note().isNull()))).write());
      assertEquals(0, f.executor.update(ROW).set(ROW.name(), "none").where(ROW.id().in(List.<Long>of())).write());
      var deleted = f.executor.deleteFrom(ROW).where(ROW.id().in(List.of(1L, 3L)))
          .returning(WriteReceiptPro.all()).write();
      assertEquals(2, deleted.affectedRows());
      assertEquals(List.of(1L, 3L), deleted.get().stream().map(WriteReceipt::id).sorted().toList());
      assertEquals(1, f.executor.update(ROW).set(ROW.name(), "all").allowAllRows().write());
      var changed = f.executor.findById(ROW, 2L).orElseThrow();
      assertEquals(2L, changed.version());
      assertThrows(OptimisticLockException.class,
          () -> f.executor.updateById(ROW, new WriteRow(2L, "stale", null, 0L)));
      assertEquals(1, f.executor.deleteFrom(ROW).allowAllRows().write());
    }
  }

  @Test
  void collectionStagesSnapshotInputsAndRefuseNonemptyExecutionUntilT05() throws Exception {
    try (var f = new Fixture()) {
      var values = new ArrayList<WriteRow>();
      var empty = f.executor.insertInto(ROW).values(values);
      values.add(new WriteRow(1L, "later", null, null));
      assertEquals(0, empty.write());
      assertTrue(empty.returning().write().isEmpty());
      assertTrue(empty.returning(WriteViewPro.of(ROW.name(), ROW.note())).write().isEmpty());
      assertTrue(empty.returning(WriteReceiptPro.all()).write().isEmpty());
      assertThrows(MutationException.class, () -> f.executor.insertInto(ROW).values(values).write());
      assertThrows(MutationException.class, () -> f.executor.insertInto(ROW).values(values).returning().write());
      assertThrows(NullPointerException.class, () -> f.executor.insertInto(ROW).values(java.util.Arrays.asList((WriteRow) null)));
      assertEquals(0, f.acquisitions.get());
    }
  }

  @Test
  void returningProjectionAppendsHiddenKeyAndKeepsConfirmedKeyAfterExternalRollback() throws Exception {
    try (var f = new Fixture()) {
      var table = UuidStringIdBeanTable.uuidStringIdBeanTable;
      var bean = new UuidStringIdBean();
      f.connection.setAutoCommit(false);
      var result = f.executor.insertInto(table).value(bean)
          .returning(WriteViewPro.of(table.name(), table.name())).write();
      assertEquals(new WriteView("new", "new"), result.get());
      assertEquals(1, bean.writes);
      String key = bean.getId();
      assertNotNull(key);
      f.connection.rollback();
      assertEquals(key, bean.getId());
      assertEquals(1, result.affectedRows());
      assertFalse(f.connection.isClosed());
      assertTrue(f.executor.findById(table, key).isEmpty());
      f.connection.setAutoCommit(true);
    }
  }

  @Test
  void sharesValueFreePlansAcrossRequestsAndSessionsAndClearsReusedRequests() throws Exception {
    try (var f = new Fixture()) {
      f.executor.insertInto(ROW).value(new WriteRow(1L, "one", null, null)).write();
      var request = f.executor.update(ROW).set(ROW.name(), "changed").where(ROW.id().eq(1L)).returning();
      int baseline = f.compilations.get();
      request.write();
      assertEquals(baseline + 1, f.compilations.get());
      request.write();
      f.executor.update(ROW).set(ROW.name(), "another value").where(ROW.id().eq(99L)).returning().write();
      try (var session = f.executor.beginTransaction()) {
        session.update(ROW).set(ROW.name(), "session").where(ROW.id().eq(1L)).returning().write();
        session.commit();
      }
      assertEquals(baseline + 1, f.compilations.get());
      assertTrue(f.executor.mutationPlanCacheStatistics().hitCount() >= 3);
      f.executor.clearMutationPlanCache();
      assertEquals(0, f.executor.mutationPlanCacheStatistics().size());
      request.write();
      assertEquals(baseline + 2, f.compilations.get());
    }
  }

  private final class Fixture implements AutoCloseable {
    final Connection connection;
    final SkisExecutor executor;
    final AtomicInteger acquisitions = new AtomicInteger();
    final AtomicInteger statements = new AtomicInteger();
    final AtomicInteger compilations = new AtomicInteger();

    Fixture() throws Exception {
      DataSource source = createDataSource();
      connection = source.getConnection();
      try (var sql = connection.createStatement()) {
        sql.execute("CREATE TEMPORARY TABLE \"write_row\" (\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(100) NOT NULL, \"note\" VARCHAR(100), \"version\" BIGINT NOT NULL)");
        sql.execute("CREATE TEMPORARY TABLE \"uuidstringidbean\" (\"Generated Key\" VARCHAR(36) PRIMARY KEY, \"name\" VARCHAR(100) NOT NULL)");
      }
      executor = Skis.builder(source).dialect(dialect())
          .runtimeModels(List.of(WriteRowRuntimeModel.MODEL, UuidStringIdBeanRuntimeModel.MODEL))
          .connectionProvider(new ConnectionProvider() {
            @Override public Connection acquire(ExecutionContext context) {
              acquisitions.incrementAndGet(); return connection;
            }
            @Override public void release(Connection released, ExecutionContext context) {
              assertSame(connection, released);
            }
          }).listener(new ExecutionListener() {
            @Override public void statementStarted(StatementStartedEvent event) { statements.incrementAndGet(); }
            @Override public void compilationCompleted(CompilationCompletedEvent event) { compilations.incrementAndGet(); }
          }).build();
    }

    @Override public void close() throws Exception { connection.close(); }
  }
}
