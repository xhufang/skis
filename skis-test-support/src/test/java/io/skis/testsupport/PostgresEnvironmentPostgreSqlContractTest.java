package io.skis.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

@PostgresContract
class PostgresEnvironmentPostgreSqlContractTest {
  @Test
  void isolatesFixedSchemasAndDropsDatabasesAfterSuccessAndFailure(
      PostgresEnvironment environment, PGSimpleDataSource observer) throws Exception {
    String first;
    String second;
    try (var left = environment.database(); var right = environment.database()) {
      first = left.dataSource().getDatabaseName();
      second = right.dataSource().getDatabaseName();
      assertNotEquals(first, second);
      for (var database : new PostgresEnvironment.Database[] {left, right}) {
        try (Connection connection = database.dataSource().getConnection();
            var statement = connection.createStatement()) {
          statement.execute("CREATE SCHEMA shelter");
          statement.execute("CREATE TABLE shelter.same_name(id BIGINT PRIMARY KEY)");
          statement.execute("INSERT INTO shelter.same_name VALUES (1)");
        }
      }
      assertTrue(exists(observer, first));
      assertTrue(exists(observer, second));
    }
    assertFalse(exists(observer, first));
    assertFalse(exists(observer, second));

    var failed = environment.database();
    String failedName = failed.dataSource().getDatabaseName();
    assertThrows(SQLException.class, () -> {
      try (failed; Connection connection = failed.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.execute("SELECT * FROM missing_contract_table");
      }
    });
    assertFalse(exists(observer, failedName));
    failed.close(); // Successful cleanup is idempotent.
  }

  private static boolean exists(PGSimpleDataSource source, String name) throws Exception {
    try (Connection connection = source.getConnection();
        PreparedStatement statement = connection.prepareStatement(
            "SELECT count(*) FROM pg_database WHERE datname = ?")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        long count = rows.getLong(1);
        assertTrue(count == 0 || count == 1);
        assertEquals(16, connection.getMetaData().getDatabaseMajorVersion());
        return count == 1;
      }
    }
  }
}
