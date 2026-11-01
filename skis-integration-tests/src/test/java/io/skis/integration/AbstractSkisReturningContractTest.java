package io.skis.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.skis.core.*;
import io.skis.core.spi.*;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectFeature;
import io.skis.integration.ids.UuidStringIdBean;
import io.skis.integration.ids.skis.UuidStringIdBeanRuntimeModel;
import io.skis.integration.ids.skis.UuidStringIdBeanTable;
import io.skis.integration.write.*;
import io.skis.integration.write.skis.*;
import io.skis.jdbc.spi.ConnectionProvider;
import io.skis.mutation.*;
import io.skis.query.Sql;
import io.skis.query.Row2;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** M17/M22-M29: identical generated mappings and assertions on both real drivers. */
abstract class AbstractSkisReturningContractTest {
  private static final WriteRowTable ROW = WriteRowTable.writeRowTable;
  protected abstract DataSource createDataSource();
  protected abstract Dialect dialect();

  @Test
  void entityReturningKeepsVersionChecksAcrossRepeatedRequests() throws Exception {
    for (boolean checked : List.of(false, true)) {
      try (var f = new Fixture()) {
        f.executor.insertInto(ROW).value(new WriteRow(1L, "before", null, null)).write();
        var input = new WriteRow(1L, "after", "updated", checked ? 0L : null);
        var update = f.executor.updateById(ROW).value(input).returning();
        var written = update.write();
        assertEquals(1, written.affectedRows());
        assertEquals(new WriteRow(1L, "after", "updated", 1L), written.one().get());
        int compilations = f.compilations.get();
        if (checked) assertThrows(OptimisticLockException.class, update::write);
        else assertEquals(2L, update.write().one().get().version());
        assertEquals(compilations, f.compilations.get());
        assertEquals(checked ? 0L : null, input.version());

        var delete = f.executor.deleteById(ROW)
            .value(new WriteRow(1L, "ignored", null, checked ? 1L : null))
            .returning(ROW.id(), ROW.name());
        var deleted = delete.write();
        assertEquals(1, deleted.affectedRows());
        assertEquals(new Row2<>(1L, "after"), deleted.one().get());
        compilations = f.compilations.get();
        if (checked) assertThrows(OptimisticLockException.class, delete::write);
        else {
          var absent = delete.write();
          assertEquals(0, absent.affectedRows());
          assertTrue(absent.rows().isEmpty());
          assertFalse(absent.one().hasRow());
        }
        assertEquals(compilations, f.compilations.get());
      }
    }
  }

  @Test
  void entityReturningPreservesPresentNullScalarsAndValidatesProjectionScopeBeforeJdbc() throws Exception {
    try (var f = new Fixture()) {
      f.executor.insertInto(ROW).value(new WriteRow(1L, "before", null, null)).write();
      var entity = new WriteRow(1L, "after", null, null);
      var written = f.executor.updateById(ROW).value(entity).returning(ROW.note()).write();
      assertEquals(1, written.affectedRows());
      assertTrue(written.one().hasRow());
      assertTrue(written.one().isNull());
      assertEquals(java.util.Collections.singletonList(null), written.rows());
      int acquisitions = f.acquisitions.get();
      var foreign = ROW.as("foreign");
      assertThrows(SkisException.class,
          () -> f.executor.updateById(ROW).value(entity).returning(foreign.name()).write());
      assertEquals(acquisitions, f.acquisitions.get());
    }
  }

  @Test
  void unversionedEntityReturningSupportsScalarAndFullRowImages() throws Exception {
    try (var f = new Fixture()) {
      var table = UuidStringIdBeanTable.uuidStringIdBeanTable;
      var bean = new UuidStringIdBean();
      bean.setName("before");
      f.executor.insertInto(table).value(bean).write();
      String id = Objects.requireNonNull(bean.getId());
      bean.setName("after");
      var update = f.executor.updateById(table).value(bean).returning(table.name());
      assertEquals(List.of("after"), update.write().rows());
      assertEquals("after", update.write().one().get());
      var delete = f.executor.deleteById(table).value(bean).returning();
      var row = delete.write().one().get();
      assertEquals(id, row.getId());
      assertEquals("after", row.getName());
      assertFalse(delete.write().one().hasRow());
    }
  }

  @Test
  void valuesBatchesGenerateUuidArgumentsForCountAndReturningChunks() throws Exception {
    try (var f = new Fixture()) {
      var table = UuidStringIdBeanTable.uuidStringIdBeanTable;
      var options = new BatchOptions(BatchMode.VALUES, 2, 1024, 8192);
      var counted = new ArrayList<UuidStringIdBean>();
      var returned = new ArrayList<UuidStringIdBean>();
      for (int index = 0; index < 3; index++) {
        var first = new UuidStringIdBean();
        if (index == 0) first.setId("00000000-0000-0000-0000-000000000001");
        first.setName("count-" + index);
        counted.add(first);
        var second = new UuidStringIdBean();
        second.setName("return-" + index);
        returned.add(second);
      }
      assertEquals(3, f.executor.insertInto(table).values(counted).batchOptions(options).write());
      int acquisitions = f.acquisitions.get(), statements = f.statements.get();
      var result = f.executor.insertInto(table).values(returned).batchOptions(options).returning().write();
      assertEquals(3, result.affectedRows());
      assertEquals(3, result.rows().size());
      assertEquals(3, result.rows().stream().map(UuidStringIdBean::getId).distinct().count());
      assertTrue(result.rows().stream().allMatch(row -> row.getId() != null));
      assertEquals(java.util.Set.of("return-0", "return-1", "return-2"),
          new java.util.HashSet<>(result.rows().stream().map(UuidStringIdBean::getName).toList()));
      assertEquals(acquisitions + 1, f.acquisitions.get());
      assertEquals(statements + 2, f.statements.get());
      assertEquals(3, f.executor.deleteById(table).values(result.rows())
          .batchOptions(options.withMode(BatchMode.ADD_BATCH)).write());
      acquisitions = f.acquisitions.get();
      for (String invalid : List.of("", "not-a-uuid", "00000000-0000-0000-0000-00000000000g",
          "00000000x0000-0000-0000-000000000000", "A0000000-0000-0000-0000-000000000000",
          "/0000000-0000-0000-0000-000000000000")) {
        var bean = new UuidStringIdBean();
        bean.setId(invalid);
        assertThrows(BatchWriteException.class,
            () -> f.executor.insertInto(table).values(List.of(bean)).batchOptions(options).write());
        assertEquals(acquisitions, f.acquisitions.get());
      }
    }
  }

  @Test
  void batchPreflightChecksEveryVersionShapeBeforeAcquiringAConnection() throws Exception {
    for (boolean memoryBudget : List.of(false, true)) {
      try (var f = new Fixture()) {
        f.executor.insertInto(ROW).values(List.of(new WriteRow(1L, "one", null, null),
            new WriteRow(2L, "two", null, null))).write();
        var options = new BatchOptions(BatchMode.ADD_BATCH, 3,
            memoryBudget ? 1024 : 3, memoryBudget ? 160 : 8192);
        assertEquals(2, f.executor.updateById(ROW).values(List.of(
            new WriteRow(1L, "allowed", null, null), new WriteRow(2L, "allowed", null, null)))
            .batchOptions(options).write());
        int acquisitions = f.acquisitions.get();
        var failure = assertThrows(BatchWriteException.class, () ->
            f.executor.updateById(ROW).values(List.of(new WriteRow(1L, "unexecuted", null, null),
                new WriteRow(2L, "too-wide", null, 1L))).batchOptions(options).write());
        assertEquals(acquisitions, f.acquisitions.get());
        assertEquals(BatchResult.Status.NOT_EXECUTED, failure.result().items().getFirst().status());
        assertEquals(BatchResult.Status.FAILED, failure.result().items().get(1).status());
        assertEquals(0, failure.result().knownAffectedRows());
        assertEquals("allowed", f.executor.findById(ROW, 1L).orElseThrow().name());
        assertEquals("allowed", f.executor.findById(ROW, 2L).orElseThrow().name());
      }
    }
  }

  @Test
  void batchCountValidationPreservesDriverFactsWithoutRetrying() throws Exception {
    for (BatchMode mode : BatchMode.values()) {
      for (int[] counts : List.of(new int[0], new int[] {-42}, new int[] {0}, new int[] {2},
          new int[] {java.sql.Statement.SUCCESS_NO_INFO}, new int[] {java.sql.Statement.EXECUTE_FAILED})) {
        if (mode == BatchMode.VALUES && counts.length == 0) continue;
        try (var f = new Fixture()) {
          var executions = new AtomicInteger();
          var released = new AtomicInteger();
          Connection connection = jdbcProxy(Connection.class, (ignored, method, arguments) -> {
            var value = invokeJdbc(f.connection, method, arguments);
            if (!method.getName().equals("prepareStatement")) return value;
            var statement = (PreparedStatement) Objects.requireNonNull(value);
            return jdbcProxy(PreparedStatement.class, (proxy, action, inputs) -> {
              if (action.getName().equals("executeBatch")) {
                executions.incrementAndGet();
                return counts.clone();
              }
              if (action.getName().equals("executeUpdate")) {
                executions.incrementAndGet();
                return counts[0];
              }
              return invokeJdbc(statement, action, inputs);
            });
          });
          var executor = Skis.builder(f.source).dialect(dialect())
              .runtimeModels(List.of(WriteRowRuntimeModel.MODEL))
              .connectionProvider(new ConnectionProvider() {
                @Override public Connection acquire(ExecutionContext context) { return connection; }
                @Override public void release(Connection actual, ExecutionContext context) {
                  assertSame(connection, actual);
                  released.incrementAndGet();
                }
              }).build();
          var failure = assertThrows(BatchWriteException.class, () -> executor.insertInto(ROW)
              .values(List.of(new WriteRow(1L, "unexecuted", null, null))).batchMode(mode).write());
          assertEquals(SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT, failure.code());
          assertEquals(1, executions.get());
          assertEquals(1, released.get());
          boolean known = counts.length == 1 && counts[0] >= 0;
          assertEquals(known, failure.result().affectedRowsKnown());
          assertEquals(known ? counts[0] : 0, failure.result().knownAffectedRows());
          assertEquals(known, failure.result().items().getFirst().countKnown());
          assertEquals(known ? counts[0] : 0, failure.result().items().getFirst().affectedRows());
        }
      }
    }
  }

  @Test
  void insertUpdateDeleteReturnFullRowsAndBothProjectionKinds() throws Exception {
    try (var f = new Fixture()) {
      var full = f.executor.insertInto(ROW).value(new WriteRow(1L, "one", null, null)).returning().write();
      assertEquals(new WriteRow(1L, "one", null, 0L), full.one().get());
      assertEquals(1, full.affectedRows());
      var reusable = f.executor.insertInto(ROW).value(new WriteRow(2L, "two", "note", null))
          .returning(WriteViewPro.of(ROW.name(), ROW.note())).write();
      assertEquals(new WriteView("two", "note"), reusable.one().get());
      var special = f.executor.insertInto(ROW).value(new WriteRow(3L, "three", null, null))
          .returning(WriteReceiptPro.all()).write();
      assertEquals(new WriteReceipt(3L, "three"), special.one().get());

      var updated = f.executor.update(ROW).set(ROW.name(), "changed")
          .where(ROW.id().in(List.of(1L, 2L))).returning().write();
      assertEquals(2, updated.affectedRows());
      assertEquals(List.of(1L, 2L), updated.rows().stream().map(WriteRow::id).sorted().toList());
      assertTrue(updated.rows().stream().allMatch(row -> row.version() == 0L));
      assertThrows(UnsupportedOperationException.class, () -> updated.rows().clear());
      assertEquals(new WriteView("changed", null), f.executor.update(ROW).setNull(ROW.note())
          .where(ROW.id().eq(1L)).returning(WriteViewPro.of(ROW.name(), ROW.note())).write().rows().getFirst());
      assertEquals(new WriteReceipt(3L, "three"), f.executor.update(ROW).set(ROW.note(), "new")
          .where(ROW.id().eq(3L)).returning(WriteReceiptPro.all()).write().rows().getFirst());

      var deleted = f.executor.deleteFrom(ROW).where(ROW.id().eq(1L)).returning().write();
      assertEquals(new WriteRow(1L, "changed", null, 0L), deleted.rows().getFirst());
      assertEquals(new WriteView("changed", "note"), f.executor.deleteFrom(ROW).where(ROW.id().eq(2L))
          .returning(WriteViewPro.of(ROW.name(), ROW.note())).write().rows().getFirst());
      assertEquals(new WriteReceipt(3L, "three"), f.executor.deleteFrom(ROW).where(ROW.id().eq(3L))
          .returning(WriteReceiptPro.all()).write().rows().getFirst());
      assertTrue(f.executor.deleteFrom(ROW).where(ROW.id().eq(1L)).returning().write().rows().isEmpty());
      var empty = f.executor.update(ROW).set(ROW.name(), "absent").where(ROW.id().eq(99L)).returning().write();
      assertEquals(0, empty.affectedRows());
      assertTrue(empty.one().isEmpty());
      int statements = f.statements.get();
      full.one().map(WriteRow::name);
      full.one().toOptional();
      full.rows().stream().count();
      empty.one().orElse(null);
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
      assertThrows(MutationException.class, () -> f.executor.update(ROW).set(ROW.name(), "a").set(ROW.name(), "b"));
      assertThrows(RuntimeException.class, () -> f.executor.update(ROW).set(ROW.name(), (String) null).allowAllRows().write());
      assertMutationShapeFailure(() -> f.executor.update(ROW)
          .set(ROW.name(), ROW.note()).allowAllRows().write());
      assertMutationShapeFailure(() -> f.executor.update(ROW)
          .set(ROW.name(), ROW.as("other").name()).allowAllRows().write());
      assertMutationShapeFailure(() -> f.executor.deleteFrom(ROW)
          .where(ROW.as("other").id().eq(1L)).write());
      assertMutationShapeFailure(() -> f.executor.update(ROW).set(ROW.note(), "unused")
          .where(ROW.as("other").id().eq(1L)).returning().write());
      assertMutationShapeFailure(() -> f.executor.update(ROW)
          .set(ROW.name(), ROW.note()).allowAllRows().returning().write());
      assertMutationShapeFailure(() -> f.executor.deleteFrom(ROW)
          .where(ROW.as("other").id().eq(1L)).returning().write());
      assertThrows(RuntimeException.class, () -> f.executor.insertInto(ROW).value(new WriteRow(1L, "one", null, null))
          .returning(WriteViewPro.of(ROW.as("other").name(), ROW.note())).write());
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
          .set(ROW.note(), Sql.coalesce(Sql.value(String.class, null), Sql.value("count")))
          .where(ROW.id().eq(1L)).and(Sql.value(String.class, null).isNull()).write());
      assertEquals("count", f.executor.findById(ROW, 1L).orElseThrow().note());
      var returned = f.executor.update(ROW)
          .set(ROW.note(), Sql.coalesce(Sql.value(String.class, null), Sql.value("returned")))
          .where(ROW.id().eq(1L)).and(Sql.value(String.class, null).isNull()).returning().write();
      assertEquals("returned", returned.rows().getFirst().note());
      assertEquals(0L, returned.rows().getFirst().version());
      assertEquals(0, f.executor.deleteFrom(ROW).where(Sql.value("keep").isNull()).write());
      var deleted = f.executor.deleteFrom(ROW)
          .where(Sql.coalesce(Sql.value(String.class, null), Sql.value("returned")).eq(ROW.note()))
          .returning().write();
      assertEquals(1, deleted.affectedRows());
      assertEquals("returned", deleted.rows().getFirst().note());
    }
  }

  @Test
  void explicitConditionsKeepVersionUntilTheCallerAssignsIt() throws Exception {
    try (var f = new Fixture()) {
      for (long id = 1; id <= 3; id++) {
        f.executor.insertInto(ROW).value(new WriteRow(id, "row-" + id, null, null)).write();
      }
      int started = f.statements.get();
      int rows = f.executor.update(ROW).set(ROW.note(), Sql.concat(ROW.name(), Sql.value("!")))
          .where(ROW.id().eq(1L)).or(ROW.id().eq(2L)).and(ROW.name().eq("row-2")).write();
      assertEquals(1, rows, "where/and/or associate from left to right");
      assertEquals(started + 1, f.statements.get());
      assertEquals("row-2!", f.executor.findById(ROW, 2L).orElseThrow().note());
      assertEquals(1, f.executor.update(ROW).set(ROW.note(), "grouped")
          .where(ROW.id().eq(1L).or(ROW.id().eq(2L).and(ROW.note().isNull()))).write());
      assertEquals(0, f.executor.update(ROW).set(ROW.name(), "none").where(ROW.id().in(List.<Long>of())).write());
      Written<WriteReceipt> deleted =
          f.executor.deleteFrom(ROW).where(ROW.id().in(List.of(1L, 3L)))
              .<WriteReceipt>returning(WriteReceiptPro.all()).write();
      assertEquals(2, deleted.affectedRows());
      assertEquals(List.of(1L, 3L), deleted.rows().stream().map(WriteReceipt::id).sorted().toList());
      assertEquals(1, f.executor.update(ROW).set(ROW.name(), "all").allowAllRows().write());
      var changed = f.executor.findById(ROW, 2L).orElseThrow();
      assertEquals(0L, changed.version());
      assertEquals(1, f.executor.update(ROW).set(ROW.version(), 2L).where(ROW.id().eq(2L)).write());
      assertThrows(OptimisticLockException.class,
          () -> f.executor.updateById(ROW).value(new WriteRow(2L, "stale", null, 0L)).write());
      assertEquals(1, f.executor.deleteFrom(ROW).allowAllRows().write());
    }
  }

  @Test
  void collectionStagesSnapshotInputsAndRequireExplicitValuesForReturning() throws Exception {
    try (var f = new Fixture()) {
      var values = new ArrayList<WriteRow>();
      var empty = f.executor.insertInto(ROW).values(values);
      values.add(new WriteRow(1L, "later", null, null));
      assertEquals(0, empty.write());
      assertTrue(empty.returning().write().rows().isEmpty());
      assertTrue(empty.returning(WriteViewPro.of(ROW.name(), ROW.note())).write().rows().isEmpty());
      assertTrue(empty.returning(WriteReceiptPro.all()).write().rows().isEmpty());
      assertEquals(0, f.acquisitions.get());
      assertThrows(MutationException.class, () -> f.executor.insertInto(ROW).values(values).returning().write());
      assertEquals(0, f.acquisitions.get());
      assertEquals(1, f.executor.insertInto(ROW).values(values).write());
      assertEquals(1, f.acquisitions.get());
      assertThrows(NullPointerException.class, () -> f.executor.insertInto(ROW).values(java.util.Arrays.asList((WriteRow) null)));
      assertEquals(1, f.acquisitions.get());
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
      assertEquals(new WriteView("new", "new"), result.one().get());
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

  @Test
  void independentRequestsPreserveNullRowsAndEmbeddedQueriesUseOuterOptions() throws Exception {
    try (var f = new Fixture()) {
      f.executor.insertInto(ROW).values(List.of(new WriteRow(1L, "one", null, null),
          new WriteRow(2L, "two", "note", null))).write();
      int statements = f.statements.get();
      var request = Sql.select(ROW.note()).from(ROW).where(ROW.id().eq(1L)).fetchOne();
      var inner = f.executor.select(ROW.id()).from(ROW)
          .withOptions(ExecutionOptions.builder().maxRows(1).build());
      var source = inner.as("selected_ids");
      assertEquals(statements, f.statements.get(), "construction must not execute SQL");
      var nullRow = f.executor.run(request);
      assertTrue(nullRow.hasRow());
      assertTrue(nullRow.isNull());
      assertEquals(List.of(1L, 2L), f.executor.run(Sql.select(source.column(ROW.id())).from(source)
          .orderBy(source.column(ROW.id()).asc()).fetchList()));
      assertEquals(statements + 2, f.statements.get());
    }
  }

  @Test
  void scalarAndFixedRowReturningDistinguishNullFromNoRow() throws Exception {
    try (var f = new Fixture()) {
      f.executor.insertInto(ROW).value(new WriteRow(1L, "one", null, null)).write();
      var scalar = f.executor.update(ROW).set(ROW.name(), "changed").where(ROW.id().eq(1L))
          .returning(ROW.note()).write();
      assertEquals(1, scalar.affectedRows());
      assertTrue(scalar.one().hasRow());
      assertTrue(scalar.one().isNull());
      var pair = f.executor.update(ROW).setDefault(ROW.note()).where(ROW.id().eq(1L))
          .returning(ROW.id(), ROW.note()).write();
      assertEquals(new Row2<>(1L, null), pair.one().get());
      assertThrows(NonUniqueResultException.class, () -> {
        f.executor.insertInto(ROW).value(new WriteRow(2L, "two", null, null)).write();
        f.executor.update(ROW).setNull(ROW.note()).allowAllRows().returning(ROW.id()).write().one();
      });
      assertFalse(f.executor.deleteFrom(ROW).where(ROW.id().eq(99L)).returning(ROW.note()).write().one().hasRow());
    }
  }

  @Test
  void entityBatchesChunkOnOneConnectionAndKeepMetadataVersions() throws Exception {
    try (var f = new Fixture()) {
      var rows = new ArrayList<WriteRow>();
      for (long id = 1; id <= 600; id++) rows.add(new WriteRow(id, "row-" + id, null, null));
      int acquisitions = f.acquisitions.get(), statements = f.statements.get();
      assertEquals(600, f.executor.insertInto(ROW).values(rows).write());
      assertEquals(acquisitions + 1, f.acquisitions.get());
      assertEquals(statements + 3, f.statements.get());
      acquisitions = f.acquisitions.get(); statements = f.statements.get();
      assertEquals(2, f.executor.updateById(ROW).values(List.of(
          new WriteRow(1L, "checked", null, 0L), new WriteRow(2L, "unchecked", null, null))).write());
      assertEquals(acquisitions + 1, f.acquisitions.get());
      assertEquals(statements + 2, f.statements.get(), "contiguous version shapes stay separate");
      assertEquals(1L, f.executor.findById(ROW, 1L).orElseThrow().version());
      assertEquals(1L, f.executor.findById(ROW, 2L).orElseThrow().version());
      assertEquals(2, f.executor.deleteById(ROW).values(List.of(
          new WriteRow(1L, "ignored", null, 1L), new WriteRow(2L, "ignored", null, 1L))).write());
    }
  }

  @Test
  void optimisticBatchFailureStopsLaterChunksAndRetainsInputFacts() throws Exception {
    try (var f = new Fixture()) {
      f.executor.insertInto(ROW).values(List.of(new WriteRow(1L, "one", null, null),
          new WriteRow(2L, "two", null, null))).write();
      var options = new BatchOptions(BatchMode.ADD_BATCH, 1, 1024, 8192);
      var failure = assertThrows(BatchWriteException.class, () -> f.executor.updateById(ROW).values(List.of(
          new WriteRow(1L, "stale", null, 99L), new WriteRow(2L, "unexecuted", null, 0L)))
          .batchOptions(options).write());
      assertEquals(SkisErrorCode.MUTATION_OPTIMISTIC_LOCK_CONFLICT, failure.code());
      assertEquals(BatchResult.Status.FAILED, failure.result().items().getFirst().status());
      assertEquals(BatchResult.Status.NOT_EXECUTED, failure.result().items().get(1).status());
      assertEquals(0, failure.result().knownAffectedRows());
      assertTrue(failure.result().affectedRowsKnown());
      assertEquals("two", f.executor.findById(ROW, 2L).orElseThrow().name());
    }
  }

  @Test
  void nativeValuesReturningUsesChunksWithoutAssumingInputOrder() throws Exception {
    try (var f = new Fixture()) {
      var options = new BatchOptions(BatchMode.VALUES, 2, 1024, 8192);
      var returned = f.executor.insertInto(ROW).values(List.of(new WriteRow(1L, "one", null, null),
          new WriteRow(2L, "two", "note", null), new WriteRow(3L, "three", null, null)))
          .batchOptions(options).returning(ROW.id(), ROW.note()).write();
      assertEquals(3, returned.affectedRows());
      assertEquals(List.of(1L, 2L, 3L), returned.rows().stream().map(Row2::first).sorted().toList());
      assertEquals(1, f.acquisitions.get());
      assertEquals(2, f.statements.get());
    }
  }

  @Test
  void insertSelectAndRowAssignmentsKeepOneNativeStatement() throws Exception {
    try (var f = new Fixture()) {
      f.executor.insertInto(ROW).value(new WriteRow(1L, "one", null, null)).write();
      var source = ROW.as("source_row");
      var copied = f.executor.insertInto(ROW).columns(ROW.id(), ROW.name(), ROW.version())
          .select(Sql.select(Sql.add(source.id(), 10L), source.name(), source.version()).from(source)
              .where(source.id().eq(1L))).returning(ROW.id()).write();
      assertEquals(11L, copied.one().get());
      var values = Sql.select(Sql.count(source.id()), Sql.coalesce(Sql.min(source.name()), Sql.value("none")))
          .from(source);
      int statements = f.statements.get();
      var changed = f.executor.update(ROW).set(Sql.row(ROW.version(), ROW.name()), values)
          .where(ROW.id().eq(11L)).returning(ROW.version(), ROW.name()).write();
      assertEquals(new Row2<>(2L, "one"), changed.one().get());
      assertEquals(statements + 1, f.statements.get());
      var missing = Sql.select(source.name()).from(source).where(source.id().eq(99L));
      var nullResult = f.executor.update(ROW).set(ROW.note(), missing).where(ROW.id().eq(1L))
          .returning(ROW.note()).write();
      assertTrue(nullResult.one().hasRow());
      assertTrue(nullResult.one().isNull());
    }
  }

  @Test
  void failedValuesReturningKeepsCompletedNullRowsAndTheDriverFailure() throws Exception {
    try (var f = new Fixture()) {
      var options = new BatchOptions(BatchMode.VALUES, 1, 1024, 8192);
      var failure = assertThrows(BatchWriteException.class, () -> f.executor.insertInto(ROW)
          .values(List.of(new WriteRow(1L, "first", null, null),
              new WriteRow(1L, "duplicate", null, null)))
          .batchOptions(options).returning(ROW.note()).write());
      assertEquals(java.util.Collections.singletonList(null), failure.result().rows());
      assertEquals(BatchResult.Status.SUCCEEDED, failure.result().items().getFirst().status());
      assertEquals(BatchResult.Status.UNKNOWN, failure.result().items().get(1).status());
      assertEquals(1, failure.result().knownAffectedRows());
      assertFalse(failure.result().affectedRowsKnown());
      Throwable cause = failure;
      while (cause != null && !(cause instanceof io.skis.jdbc.JdbcExecutionException))
        cause = cause.getCause();
      assertInstanceOf(io.skis.jdbc.JdbcExecutionException.class, cause);
      assertEquals(1, f.acquisitions.get());
      assertEquals(2, f.statements.get());
      assertEquals("first", f.executor.findById(ROW, 1L).orElseThrow().name());
    }
  }

  @Test
  void valuesFailuresDistinguishPreparationFromExecutionAndStopLaterChunks() throws Exception {
    for (boolean returning : List.of(false, true)) {
      for (boolean observed : List.of(false, true)) {
        var phases = observed ? List.of("prepareStatement", "setString", "execute")
            : List.of("setString", "execute");
        for (String phase : phases) {
          try (var f = new Fixture()) {
            var prepared = new AtomicInteger();
            var executions = new AtomicInteger();
            var closed = new AtomicInteger();
            var acquired = new AtomicInteger();
            var released = new AtomicInteger();
            var completed = new ArrayList<StatementCompletedEvent>();
            var driverFailure = new SQLException("injected VALUES failure", "07000");
            Connection connection = jdbcProxy(Connection.class, (ignored, method, arguments) -> {
              if (!method.getName().equals("prepareStatement"))
                return invokeJdbc(f.connection, method, arguments);
              int attempt = prepared.incrementAndGet();
              if (attempt == 2 && phase.equals("prepareStatement")) throw driverFailure;
              var statement = (PreparedStatement) Objects.requireNonNull(
                  invokeJdbc(f.connection, method, arguments));
              return jdbcProxy(PreparedStatement.class, (proxy, action, inputs) -> {
                if (action.getName().equals("close")) closed.incrementAndGet();
                if (action.getName().startsWith("execute")) {
                  int execution = executions.incrementAndGet();
                  if (execution == 2 && phase.equals("execute")) throw driverFailure;
                }
                if (phase.equals("setString") && action.getName().equals("setString")
                    && inputs != null && Objects.equals(inputs[1], "second")) throw driverFailure;
                return invokeJdbc(statement, action, inputs);
              });
            });
            var builder = Skis.builder(f.source).dialect(dialect())
                .runtimeModels(List.of(WriteRowRuntimeModel.MODEL))
                .connectionProvider(new ConnectionProvider() {
                  @Override public Connection acquire(ExecutionContext context) {
                    acquired.incrementAndGet();
                    return connection;
                  }
                  @Override public void release(Connection actual, ExecutionContext context) {
                    assertSame(connection, actual);
                    released.incrementAndGet();
                  }
                });
            if (observed) builder.listener(new ExecutionListener() {
              @Override public void statementCompleted(StatementCompletedEvent event) { completed.add(event); }
            });
            var executor = builder.build();
            var write = executor.insertInto(ROW).values(List.of(
                new WriteRow(1L, "first", null, null),
                new WriteRow(2L, "second", null, null),
                new WriteRow(3L, "unexecuted", null, null)))
                .batchOptions(new BatchOptions(BatchMode.VALUES, 1, 1024, 8192));
            var failure = assertThrows(BatchWriteException.class, () -> {
              if (returning) write.returning(ROW.note()).write();
              else write.write();
            });
            boolean attempted = phase.equals("execute");
            var result = failure.result();
            assertEquals(BatchResult.Status.SUCCEEDED, result.items().get(0).status());
            assertEquals(1, result.items().get(0).affectedRows());
            assertEquals(attempted ? BatchResult.Status.UNKNOWN : BatchResult.Status.NOT_EXECUTED,
                result.items().get(1).status());
            assertEquals(!attempted, result.items().get(1).countKnown());
            assertEquals(BatchResult.Status.NOT_EXECUTED, result.items().get(2).status());
            assertEquals(1, result.knownAffectedRows());
            assertEquals(!attempted, result.affectedRowsKnown());
            assertEquals(returning ? java.util.Collections.singletonList(null) : List.of(), result.rows());
            Throwable original = failure;
            while (original.getCause() != null) original = original.getCause();
            assertSame(driverFailure, original);
            assertEquals(1, acquired.get());
            assertEquals(1, released.get());
            assertEquals(observed ? 2 : 1, prepared.get());
            assertEquals(attempted ? 2 : 1, executions.get());
            assertEquals(observed && !phase.equals("prepareStatement") ? 2 : 1, closed.get());
            assertEquals(observed ? 2 : 0, completed.size());
            if (observed) assertEquals(ExecutionOutcome.FAILED, completed.getLast().outcome());
            assertTrue(f.executor.findById(ROW, 1L).isPresent());
            assertTrue(f.executor.findById(ROW, 2L).isEmpty());
            assertTrue(f.executor.findById(ROW, 3L).isEmpty());
          }
        }
      }
    }
  }

  @Test
  void nativeWriteSourcesEitherExecuteOrFailBeforeJdbc() throws Exception {
    try (var f = new Fixture()) {
      f.executor.insertInto(ROW).values(List.of(new WriteRow(1L, "one", null, null),
          new WriteRow(2L, "two", null, null))).write();
      var source = ROW.as("write_source");
      var update = f.executor.update(ROW).from(source).set(ROW.note(), source.name())
          .where(ROW.id().eq(2L)).and(source.id().eq(1L)).returning(ROW.note());
      var delete = f.executor.deleteFrom(ROW).using(source)
          .where(ROW.id().eq(2L)).and(source.id().eq(1L)).returning(ROW.id());
      int acquisitions = f.acquisitions.get();
      if (dialect().capabilities().supports(DialectFeature.UPDATE_FROM)) {
        assertEquals("one", update.write().one().get());
        assertEquals(2L, delete.write().one().get());
      } else {
        assertEquals(SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED, assertThrows(SkisException.class, update::write).code());
        assertEquals(SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED, assertThrows(SkisException.class, delete::write).code());
        assertEquals(acquisitions, f.acquisitions.get());
      }
    }
  }

  @Test
  void orderedMergeActionsRejectMixedReturningAndDeleteOnlyReturnsOldRows() throws Exception {
    try (var f = new Fixture()) {
      f.executor.insertInto(ROW).values(List.of(new WriteRow(1L, "one", null, null),
          new WriteRow(2L, "two", "old", null))).write();
      var id = Sql.output(ROW.id(), "source_id");
      var source = Sql.values(row -> row.value(id, 1L));
      var mixed = f.executor.mergeInto(ROW).using(source).on(ROW.id().eq(source.column(id)))
          .whenMatched(source.column(id).eq(1L)).thenDelete()
          .whenMatched().thenUpdate().set(ROW.note(), "unreached");
      int acquisitions = f.acquisitions.get();
      assertThrows(MutationException.class, () -> mixed.returning(ROW.id()).write());
      assertEquals(acquisitions, f.acquisitions.get());
      if (dialect().capabilities().supports(DialectFeature.MERGE_USING)) {
        assertEquals(1, mixed.write());
        assertTrue(f.executor.findById(ROW, 1L).isEmpty());
        var second = Sql.values(row -> row.value(id, 2L));
        var removed = f.executor.mergeInto(ROW).using(second).on(ROW.id().eq(second.column(id)))
            .whenMatched().thenDelete().returning(ROW.id(), ROW.note()).write();
        assertEquals(1, removed.affectedRows());
        assertEquals(new Row2<>(2L, "old"), removed.one().get());
        assertTrue(f.executor.findById(ROW, 2L).isEmpty());
      } else {
        assertEquals(SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED, assertThrows(SkisException.class, mixed::write).code());
        assertEquals(acquisitions, f.acquisitions.get());
      }
    }
  }

  @Test
  void strictMergeRejectsNullableOrSubstitutedSourceKeysBeforeJdbc() throws Exception {
    try (var f = new Fixture()) {
      var id = Sql.output(ROW.id(), "id");
      var alternate = Sql.output(ROW.id(), "alternate_id");
      var source = Sql.values(row -> row.value(id, 1L).value(alternate, 2L));
      var nullableId = Sql.outputNullable(ROW.id(), "nullable_id");
      var nullable = Sql.values(row -> row.value(nullableId, 1L));
      var table = ROW.as("source");
      var tableUpdate = f.executor.mergeInto(ROW).using(table).on(ROW.id().eq(table.id()))
          .whenMatched().thenUpdate().set(ROW.note(), "invalid");
      assertEquals(SkisErrorCode.MUTATION_PARAMETER_SHAPE,
          assertThrows(MutationException.class, () -> tableUpdate.expectedVersion(0L)).code());
      var reversedNonKey = f.executor.mergeInto(ROW).using(source).on(source.column(id).eq(ROW.version()))
          .whenMatched().thenUpdate().set(ROW.note(), "invalid").expectedVersion(0L)
          .whenNotMatched().thenInsert(row -> row.value(ROW.id(), source.column(id)).value(ROW.name(), Sql.value("invalid")));
      var literalKey = f.executor.mergeInto(ROW).using(source).on(ROW.id().eq(source.column(id)))
          .whenMatched().thenUpdate().set(ROW.note(), "invalid").expectedVersion(0L)
          .whenNotMatched().thenInsert(row -> row.value(ROW.id(), Sql.value(2L)).value(ROW.name(), Sql.value("invalid")));
      var substituted = f.executor.mergeInto(ROW).using(source).on(ROW.id().eq(source.column(id)))
          .whenMatched().thenUpdate().set(ROW.note(), "invalid").expectedVersion(0L)
          .whenNotMatched().thenInsert(row -> row.value(ROW.id(), source.column(alternate)).value(ROW.name(), Sql.value("invalid")));
      var nullableKey = f.executor.mergeInto(ROW).using(nullable).on(ROW.id().eq(nullable.column(nullableId)))
          .whenMatched().thenUpdate().set(ROW.note(), "invalid").expectedVersion(0L)
          .whenNotMatched().thenInsert(row -> row.value(ROW.id(), nullable.column(nullableId)).value(ROW.name(), Sql.value("invalid")));
      for (var invalid : List.of(reversedNonKey, literalKey, substituted, nullableKey))
        assertThrows(MutationException.class, invalid::write);
      assertEquals(0, f.acquisitions.get());
    }
  }

  @Test
  void mergeBranchConditionsComposeBeforeTheirActions() throws Exception {
    try (var f = new Fixture()) {
      f.executor.insertInto(ROW).value(new WriteRow(1L, "one", null, null)).write();
      var id = Sql.output(ROW.id(), "id");
      var source = Sql.values(row -> row.value(id, 1L));
      var request = f.executor.mergeInto(ROW).using(source).on(ROW.id().eq(source.column(id)))
          .whenMatched().and(ROW.name().eq("one")).and(source.column(id).eq(1L))
          .thenUpdate().set(ROW.note(), "composed")
          .whenNotMatched().and(source.column(id).eq(2L))
          .thenInsert(row -> row.value(ROW.id(), source.column(id)).value(ROW.name(), Sql.value("two")));
      int acquisitions = f.acquisitions.get();
      if (dialect().capabilities().supports(DialectFeature.MERGE_USING)) {
        assertEquals(1, request.write());
        assertEquals("composed", f.executor.findById(ROW, 1L).orElseThrow().note());
      } else {
        assertEquals(SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED, assertThrows(SkisException.class, request::write).code());
        assertEquals(acquisitions, f.acquisitions.get());
      }
    }
  }

  @Test
  void tableMergeKeepsVersionsAndStrictValuesRequireAttributablePrimaryKeys() throws Exception {
    try (var f = new Fixture()) {
      f.executor.insertInto(ROW).values(List.of(new WriteRow(1L, "one", null, null),
          new WriteRow(2L, "two", null, null))).write();
      var table = ROW.as("merge_source");
      var tableMerge = f.executor.mergeInto(ROW).using(table).on(ROW.id().eq(table.id()))
          .whenMatched().thenUpdate().set(ROW.note(), table.name()).returning(ROW.id(), ROW.version());
      int acquisitions = f.acquisitions.get();
      if (dialect().capabilities().supports(DialectFeature.MERGE_USING)) {
        var written = tableMerge.write();
        assertEquals(2, written.affectedRows());
        assertTrue(written.rows().stream().allMatch(row -> row.second() == 0L));
      } else {
        assertEquals(SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED, assertThrows(SkisException.class, tableMerge::write).code());
        assertEquals(acquisitions, f.acquisitions.get());
      }
      var id = Sql.output(ROW.id(), "checked_id");
      var source = Sql.values(row -> row.value(id, 1L));
      var nonKey = f.executor.mergeInto(ROW).using(source).on(ROW.version().eq(source.column(id)))
          .whenMatched().thenUpdate().set(ROW.note(), "invalid").expectedVersion(0L)
          .whenNotMatched().thenInsert(row -> row
              .value(ROW.id(), source.column(id)).value(ROW.name(), Sql.value("inserted")));
      var filtered = f.executor.mergeInto(ROW).using(source).on(ROW.id().eq(source.column(id)))
          .whenMatched(ROW.name().eq("skip")).thenUpdate().set(ROW.note(), "invalid").expectedVersion(0L)
          .whenNotMatched().thenInsert(row -> row
              .value(ROW.id(), source.column(id)).value(ROW.name(), Sql.value("inserted")));
      acquisitions = f.acquisitions.get();
      assertThrows(MutationException.class, nonKey::write);
      assertThrows(MutationException.class, filtered::write);
      assertEquals(acquisitions, f.acquisitions.get());
      if (dialect().capabilities().supports(DialectFeature.MERGE_USING)) {
        assertEquals(1, f.executor.mergeInto(ROW).using(source).on(source.column(id).eq(ROW.id()))
            .whenMatched().thenUpdate().set(ROW.note(), "checked").expectedVersion(0L)
            .whenNotMatched().thenInsert(row -> row
                .value(ROW.id(), source.column(id)).value(ROW.name(), Sql.value("inserted"))).write());
        assertEquals(1L, f.executor.findById(ROW, 1L).orElseThrow().version());
        assertEquals(1, f.executor.mergeInto(ROW).using(source).on(ROW.id().eq(source.column(id)))
            .whenMatched().thenUpdate().set(ROW.version(), 7L).write());
        assertEquals(7L, f.executor.findById(ROW, 1L).orElseThrow().version());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T jdbcProxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static @Nullable Object invokeJdbc(
      Object target, Method method, @Nullable Object @Nullable [] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException failure) {
      throw Objects.requireNonNull(failure.getCause());
    }
  }

  private final class Fixture implements AutoCloseable {
    final DataSource source;
    final Connection connection;
    final SkisExecutor executor;
    final AtomicInteger acquisitions = new AtomicInteger();
    final AtomicInteger statements = new AtomicInteger();
    final AtomicInteger compilations = new AtomicInteger();

    Fixture() throws Exception {
      source = createDataSource();
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
