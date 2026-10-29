package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import example.skis.PetAggregatePro;
import example.skis.PetSummaryPro;
import example.skis.PetTable;
import io.skis.dialect.h2.H2Dialect;
import io.skis.query.PageRequest;
import io.skis.query.SliceRequest;
import io.skis.query.Sql;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class MinimalConsumerTest {

  @Test
  void usesTypedSingleRowMergeAndGeneratedProjectionOutsideTheReactor() throws Exception {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:merge_consumer");
    try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE \"pet\" (\"id\" BIGINT PRIMARY KEY, \"pet_name\" VARCHAR(200) NOT NULL, \"version\" BIGINT NOT NULL)");
      var executor = Skis.create(source, H2Dialect.INSTANCE);
      var pet = PetTable.petTable;
      var id = Sql.output(pet.id(), "id"); var name = Sql.output(pet.name(), "name");
      var incoming = Sql.values(row -> row.value(id, 1L).value(name, "merged")).as("incoming");
      var request = executor.mergeInto(pet).using(incoming).on(pet.id().eq(incoming.column(id)))
          .whenMatched().thenUpdate().setExpression(pet.name(), incoming.column(name))
          .expectedVersion(0L).whenNotMatched().thenInsert(row -> row
              .value(pet.id(), incoming.column(id))
              .value(pet.name(), incoming.column(name)))
          .returning(PetSummaryPro.of(pet.id(), pet.name()));
      assertEquals(List.of(new PetSummary(1L, "merged")), request.write().get());
      assertEquals(1, request.write().affectedRows());
      assertEquals(1L, executor.findById(pet, 1L).orElseThrow().version());
    }
  }

  @Test
  void groupsGeneratedAggregateProjectionsAndCountsFinalRowsOutsideTheReactor() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:aggregate_consumer");
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
      SkisExecutor executor = Skis.create(dataSource, H2Dialect.INSTANCE);
      PetTable pet = PetTable.petTable;
      executor.insertInto(pet).value(new Pet(1L, "aggregate-a", null)).write();
      executor.insertInto(pet).value(new Pet(2L, "aggregate-a", null)).write();
      executor.insertInto(pet).value(new Pet(3L, "aggregate-b", null)).write();

      var suffix = Sql.parameter(String.class, "suffix");
      var key = Sql.concat(pet.name(), Sql.value(suffix));
      var count = Sql.countAll();
      var template =
          executor
              .select(PetAggregatePro.of(key, count, Sql.sumAsBigInteger(pet.id())))
              .from(pet)
              .groupBy(key)
              .having(count.ge(1L))
              .orderBy(key.asc());
      var query = template.bind(suffix, "-first");
      List<PetAggregate> expected =
          List.of(
              new PetAggregate("aggregate-a-first", 2L, BigInteger.valueOf(3L)),
              new PetAggregate("aggregate-b-first", 1L, BigInteger.valueOf(3L)));
      assertEquals(expected, query.fetchList());
      var page = query.fetchPage(PageRequest.page(0, 1));
      assertEquals(2L, page.totalElements());
      assertEquals(expected.subList(0, 1), page.items());
      var second = query.fetchPage(PageRequest.page(1, 1), query.countQuery());
      assertEquals(2L, second.totalElements());
      assertEquals(expected.subList(1, 2), second.items());
      var slice = query.fetchSlice(SliceRequest.offset(1L, 1));
      assertEquals(expected.subList(1, 2), slice.items());
      assertFalse(slice.hasNext());
      var pastEnd = query.fetchPage(PageRequest.page(5, 1));
      assertEquals(2L, pastEnd.totalElements());
      assertTrue(pastEnd.items().isEmpty());
      var filtered = query.andHaving(count.gt(1L)).fetchPage(PageRequest.page(0, 1));
      assertEquals(1L, filtered.totalElements());
      assertEquals(expected.subList(0, 1), filtered.items());
      assertEquals(
          List.of("aggregate-a-second", "aggregate-b-second"),
          template.bind(suffix, "-second").fetchList().stream().map(PetAggregate::name).toList());

      var emptyGlobal =
          executor.select(count).from(pet).where(pet.id().in(List.of()))
              .fetchPage(PageRequest.page(0, 1));
      assertEquals(1L, emptyGlobal.totalElements());
      assertEquals(List.of(0L), emptyGlobal.items());
      var emptyGroups =
          template.where(pet.id().in(List.of())).bind(suffix, "-empty")
              .fetchPage(PageRequest.page(0, 1));
      assertEquals(0L, emptyGroups.totalElements());
      assertTrue(emptyGroups.items().isEmpty());
      assertEquals(
          Collections.singletonList(null),
          executor.select(Sql.sumAsBigInteger(Sql.value(Long.class, null)))
              .from(pet).fetchList());
      assertEquals(
          List.of(0L),
          executor.select(Sql.count(Sql.value(Long.class, null))).from(pet).fetchList());
    }
  }

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
    executor.insertInto(PetTable.petTable).value(new Pet(1L, "Mimi", null)).write();

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
