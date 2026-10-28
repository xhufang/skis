package io.skis.example.h2;

import io.skis.dialect.h2.H2Dialect;
import io.skis.example.h2.skis.PetTable;
import io.skis.runtime.Skis;
import io.skis.runtime.SkisExecutor;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;

/** Minimal external-consumer-shaped example using only the public SKIS API. */
public final class SkisH2Example {

  private SkisH2Example() {}

  public static void main(String[] args) throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:skis_example;DB_CLOSE_DELAY=-1");
    createSchema(dataSource);

    PetTable pet = PetTable.petTable;
    SkisExecutor executor = Skis.create(dataSource, H2Dialect.INSTANCE);
    executor.insertInto(pet).value(new Pet(1L, "Mimi", null)).write();
    List<Pet> selected = executor.selectFrom(pet).where(pet.name().eq("Mimi")).fetchList();
    Pet stored = selected.getFirst();

    AtomicInteger committedChanges = new AtomicInteger();
    executor.inTransaction(
        session -> {
          session.updateById(pet, new Pet(stored.id(), "Momo", stored.version()));
          session.afterCommit(committedChanges::incrementAndGet);
          return null;
        });
    if (committedChanges.get() != 1) {
      throw new IllegalStateException("afterCommit must run once after a successful JDBC commit");
    }

    executor.deleteById(pet, stored.id());
  }

  private static void createSchema(JdbcDataSource dataSource) throws Exception {
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
  }
}
