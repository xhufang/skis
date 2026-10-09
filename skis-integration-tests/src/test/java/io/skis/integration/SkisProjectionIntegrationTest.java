package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.dialect.h2.H2Dialect;
import io.skis.query.PageRequest;
import io.skis.query.QueryPlanCacheStatistics;
import io.skis.query.SelectQuery;
import io.skis.query.SingleRow;
import io.skis.query.SliceRequest;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import io.skis.testmodel.pet.Pet;
import io.skis.testmodel.pet.PetSummary;
import io.skis.testmodel.pet.skis.PetMeta;
import io.skis.testmodel.pet.skis.PetSummaryProjection;
import io.skis.testmodel.pet.skis.PetTable;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class SkisProjectionIntegrationTest {

  @Test
  void selectsScalarsAndUserRecordsWithoutReadingTheFullEntity() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor =
        SkisExecutorFactory.builder()
            .dataSource(dataSource)
            .dialect(H2Dialect.INSTANCE)
            .planCacheMaximumSize(2)
            .planCacheExpireAfterAccess(Duration.ofMinutes(5))
            .build();
    Pet pet = new Pet(7L, "Mimi", new BigDecimal("12.50"), false, null, "ignored");
    executor.insert(PetMeta.ENTITY, pet);
    executor.insert(PetMeta.ENTITY,
        new Pet(8L, "Nina", new BigDecimal("10.25"), false, null, "ignored"));

    List<String> names =
        executor
            .select(PetTable.PET.name())
            .from(PetTable.PET)
            .where(PetTable.PET.id().eq(7L))
            .fetch();
    PetSummary projected =
        executor
            .select(
                PetSummaryProjection.of(
                    PetTable.PET.id(), PetTable.PET.name(), PetTable.PET.weight()))
            .from(PetTable.PET)
            .where(PetTable.PET.id().eq(7L))
            .fetchOne()
            .orElseThrow();
    PetSummary reusedProjection = sharedTerminal(executor, true, 1, "generated projection", () ->
        executor.select(PetSummaryProjection.of(
                PetTable.PET.id(), PetTable.PET.name(), PetTable.PET.weight()))
            .from(PetTable.PET).where(PetTable.PET.id().eq(8L)).fetchOne().orElseThrow());
    String transactionalName =
        executor.inTransaction(
            session ->
                session
                    .select(PetTable.PET.name())
                    .from(PetTable.PET)
                    .where(PetTable.PET.id().eq(7L))
                    .fetchOne()
                    .orElseThrow());

    assertEquals(List.of("Mimi"), names);
    assertEquals(new PetSummary(7L, "Mimi", new BigDecimal("12.50")), projected);
    assertEquals(new PetSummary(8L, "Nina", new BigDecimal("10.25")), reusedProjection);
    assertEquals("Mimi", transactionalName);
    assertEquals(
        new QueryPlanCacheStatistics(2, 2, 0, 0, 2, 2),
        executor.queryPlanCacheStatistics());
    executor.clearQueryPlanCache();
    assertEquals(0, executor.queryPlanCacheStatistics().size());
    assertEquals(2, executor.queryPlanCacheStatistics().invalidationCount());
  }

  @Test
  void freshTerminalOperationsSharePlansAndBindCurrentValues() throws Exception {
    SkisExecutor executor = SkisExecutorFactory.create(database(), H2Dialect.INSTANCE);
    for (long id : List.of(7L, 8L, 9L)) {
      executor.insert(PetMeta.ENTITY,
          new Pet(id, "pet-" + id, new BigDecimal("1.00"), false, null, "ignored"));
    }
    for (long threshold : List.of(7L, 8L)) {
      boolean warmed = threshold == 8L;
      Supplier<SelectQuery<Pet, Long>> query = () -> {
        PetTable table = PetTable.PET.as("p");
        return executor.select(table.id()).from(table)
            .where(table.id().ge(threshold)).orderBy(table.id().asc());
      };
      List<Long> expected = threshold == 7L ? List.of(7L, 8L, 9L) : List.of(8L, 9L);
      assertEquals(expected, sharedTerminal(executor, warmed, 1, "fetchList",
          () -> query.get().fetchList()));
      assertEquals(threshold, sharedTerminal(executor, warmed, 1, "fetchFirst",
          () -> query.get().fetchFirst().orElseThrow()));
      assertEquals(expected.size(), sharedTerminal(executor, warmed, 2, "explicit-count page",
          () -> query.get().fetchPage(PageRequest.page(0, 1), query.get().countQuery())).totalElements());
      var page = sharedTerminal(executor, warmed, 2, "page",
          () -> query.get().fetchPage(PageRequest.page(0, 1)));
      assertEquals(expected.subList(0, 1), page.items());
      assertEquals(expected.size(), page.totalElements());
      assertEquals(expected.subList(0, 1),
          sharedTerminal(executor, warmed, 2, "offset slice",
              () -> query.get().fetchSlice(SliceRequest.offset(0, 1))).items());
      // Slices also look up unpaginated content for their continuation fingerprint.
      var first = sharedTerminal(executor, warmed, 2, "keyset first",
          () -> query.get().fetchSlice(SliceRequest.keysetFirst(1)));
      assertEquals(expected.subList(0, 1), first.items());
      assertEquals(expected.subList(1, 2), sharedTerminal(executor, warmed, 2, "keyset resume",
          () -> query.get().fetchSlice(
              SliceRequest.resume(first.nextContinuation().orElseThrow(), 1))).items());
      List<Long> cursorValues = sharedTerminal(executor, warmed, 1, "cursor", () -> {
        List<Long> values = new ArrayList<>();
        try (var cursor = query.get().cursor()) {
          while (cursor.advance()) {
            values.add(cursor.current());
          }
        }
        return values;
      });
      assertEquals(expected, cursorValues);
      assertEquals(expected, sharedTerminal(executor, warmed, 1, "stream", () -> {
        try (var stream = query.get().stream()) {
          return stream.stream().toList();
        }
      }));
      PetTable single = PetTable.PET.as("single");
      assertEquals(threshold, sharedTerminal(executor, warmed, 1, "fetchOne",
          () -> executor.select(single.id()).from(single)
              .where(single.id().eq(threshold)).fetchOne().orElseThrow()));
      assertEquals(SingleRow.present(threshold), sharedTerminal(executor, warmed, 1, "nullable fetchOne",
          () -> executor.selectNullable(single.id()).from(single)
              .where(single.id().eq(threshold)).fetchOne()));
    }
  }

  private static <R> R sharedTerminal(
      SkisExecutor executor, boolean warmed, long hits, String terminal, Supplier<R> action) {
    QueryPlanCacheStatistics before = executor.queryPlanCacheStatistics();
    R result = action.get();
    QueryPlanCacheStatistics after = executor.queryPlanCacheStatistics();
    if (warmed) {
      assertEquals(before.missCount(), after.missCount(), terminal + " unexpectedly missed L1");
      assertEquals(before.hitCount() + hits, after.hitCount(), terminal + " L1 lookup count");
    } else {
      assertTrue(after.hitCount() + after.missCount() > before.hitCount() + before.missCount(),
          terminal + " must enter L1 while warming");
    }
    return result;
  }

  @Test
  void executesDistinctOrderingWithH2NativeNullPlacement() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor =
        SkisExecutorFactory.builder()
            .dataSource(dataSource)
            .dialect(H2Dialect.INSTANCE)
            .build();
    executor.insert(
        PetMeta.ENTITY,
        new Pet(7L, "Mimi", new BigDecimal("12.50"), false, null, "ignored"));
    executor.insert(
        PetMeta.ENTITY,
        new Pet(8L, "Mimi", new BigDecimal("10.25"), true, null, "ignored"));

    List<String> names =
        executor
            .select(PetTable.PET.name())
            .from(PetTable.PET)
            .distinct()
            .orderBy(PetTable.PET.name().asc().nullsLast())
            .fetchList();

    assertEquals(List.of("Mimi"), names);
  }

  private static DataSource database() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:skis_projection_"
            + UUID.randomUUID().toString().replace('-', '_')
            + ";DB_CLOSE_DELAY=-1");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA \"shelter\"");
      statement.execute(
          """
          CREATE TABLE "shelter"."pet" (
            "id" BIGINT PRIMARY KEY,
            "pet_name" VARCHAR(200) NOT NULL,
            "weight" DECIMAL(6, 2) NOT NULL,
            "adopted" BOOLEAN NOT NULL,
            "version" BIGINT NOT NULL
          )
          """);
    }
    return dataSource;
  }
}
