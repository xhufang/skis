package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.skis.dialect.postgresql.PostgreSqlCodecs;
import io.skis.dialect.postgresql.PostgreSqlDialect;
import io.skis.mapping.JdbcReadContext;
import io.skis.mapping.JdbcWriteContext;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import io.skis.testmodel.types.JdbcTypes;
import io.skis.testmodel.types.skis.JdbcTypesMeta;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class SkisJdbcTypePostgreSqlContractTest {

  @Test
  void roundTripsEveryDeclaredPostgreSqlMappingThroughGeneratedCode() throws Exception {
    PGSimpleDataSource dataSource = configuredDataSource();
    prepareTypeSchema(dataSource);
    long id = positiveRandomId();
    long nullId = nextId(id);
    long emptyBytesId = nextId(nullId);
    JdbcTypes expected = JdbcTypeContractSupport.nonNullValues(id);
    JdbcTypes nulls = JdbcTypeContractSupport.nullValues(nullId);
    JdbcTypes emptyBytes = JdbcTypeContractSupport.emptyBytesValue(emptyBytesId);
    SkisExecutor executor = SkisExecutorFactory.create(dataSource, PostgreSqlDialect.INSTANCE);

    try {
      assertEquals(1, executor.insert(JdbcTypesMeta.ENTITY, expected));
      assertEquals(1, executor.insert(JdbcTypesMeta.ENTITY, nulls));
      assertEquals(1, executor.insert(JdbcTypesMeta.ENTITY, emptyBytes));

      JdbcTypeContractSupport.assertNonNullValues(
          expected, executor.findById(JdbcTypesMeta.ENTITY, id).orElseThrow());
      JdbcTypeContractSupport.assertNullValues(
          executor.findById(JdbcTypesMeta.ENTITY, nullId).orElseThrow());
      JdbcTypeContractSupport.assertNonNullValues(
          emptyBytes, executor.findById(JdbcTypesMeta.ENTITY, emptyBytesId).orElseThrow());
      JdbcTypeContractSupport.assertInvalidReadsFail(dataSource);
    } finally {
      deleteRows(dataSource, "jdbc_types", id, nullId, emptyBytesId);
    }
  }

  @Test
  void roundTripsJsonAndJsonbTextWithoutDriverSpecificObjects() throws Exception {
    PGSimpleDataSource dataSource = configuredDataSource();
    prepareJsonSchema(dataSource);
    long id = positiveRandomId();
    long nullId = nextId(id);
    long invalidJsonId = nextId(nullId);
    long invalidJsonbId = nextId(invalidJsonId);
    String json = "{\"value\":\"Milo'); DROP TABLE skis_types.json_types; --\"}";
    String jsonb = "{\"enabled\":true}";

    try {
      insertJson(dataSource, id, json, jsonb);
      insertJson(dataSource, nullId, null, null);

      try (Connection connection = dataSource.getConnection();
          PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT \"jsonValue\", \"jsonbValue\" "
                      + "FROM \"skis_types\".\"json_types\" WHERE \"id\" = ?")) {
        statement.setLong(1, id);
        try (ResultSet resultSet = statement.executeQuery()) {
          resultSet.next();
          assertEquals(
              json, PostgreSqlCodecs.JSON.read(resultSet, 1, JdbcReadContext.EMPTY));
          assertEquals(
              jsonb,
              PostgreSqlCodecs.JSON
                  .read(resultSet, 2, JdbcReadContext.EMPTY)
                  .replaceAll("\\s+", ""));
        }

        statement.setLong(1, nullId);
        try (ResultSet resultSet = statement.executeQuery()) {
          resultSet.next();
          assertNull(PostgreSqlCodecs.JSON.read(resultSet, 1, JdbcReadContext.EMPTY));
          assertNull(PostgreSqlCodecs.JSON.read(resultSet, 2, JdbcReadContext.EMPTY));
        }
      }

      assertThrows(
          SQLException.class, () -> insertJson(dataSource, invalidJsonId, "{invalid", jsonb));
      assertThrows(
          SQLException.class, () -> insertJson(dataSource, invalidJsonbId, json, "{invalid"));
    } finally {
      deleteRows(dataSource, "json_types", id, nullId, invalidJsonId, invalidJsonbId);
    }
  }

  private static PGSimpleDataSource configuredDataSource() {
    String url = System.getenv("SKIS_POSTGRES_URL");
    assumeTrue(url != null && url.startsWith("jdbc:postgresql:"));
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(url);
    dataSource.setUser(environmentOrDefault("SKIS_POSTGRES_USER", "skis"));
    dataSource.setPassword(environmentOrDefault("SKIS_POSTGRES_PASSWORD", "skis"));
    return dataSource;
  }

  private static void prepareTypeSchema(PGSimpleDataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS \"skis_types\"");
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS "skis_types"."jdbc_types" (
            "id" BIGINT PRIMARY KEY,
            "primitiveBoolean" BOOLEAN NOT NULL,
            "nullableBoolean" BOOLEAN,
            "primitiveByte" SMALLINT NOT NULL,
            "nullableByte" SMALLINT,
            "primitiveShort" SMALLINT NOT NULL,
            "nullableShort" SMALLINT,
            "primitiveInteger" INTEGER NOT NULL,
            "nullableInteger" INTEGER,
            "primitiveLong" BIGINT NOT NULL,
            "nullableLong" BIGINT,
            "primitiveFloat" REAL NOT NULL,
            "nullableFloat" REAL,
            "primitiveDouble" DOUBLE PRECISION NOT NULL,
            "nullableDouble" DOUBLE PRECISION,
            "primitiveCharacter" CHARACTER(1) NOT NULL,
            "nullableCharacter" CHARACTER(1),
            "requiredString" VARCHAR(200) NOT NULL,
            "nullableString" VARCHAR(200),
            "bigIntegerValue" NUMERIC(40, 0),
            "bigDecimalValue" NUMERIC(30, 6),
            "bytesValue" BYTEA,
            "uuidValue" UUID,
            "instantValue" TIMESTAMP(6) WITH TIME ZONE,
            "localDateValue" DATE,
            "localTimeValue" TIME(6) WITHOUT TIME ZONE,
            "localDateTimeValue" TIMESTAMP(6) WITHOUT TIME ZONE,
            "offsetTimeValue" TIME(6) WITH TIME ZONE,
            "offsetDateTimeValue" TIMESTAMP(6) WITH TIME ZONE,
            "sqlDateValue" DATE,
            "sqlTimeValue" TIME WITHOUT TIME ZONE,
            "sqlTimestampValue" TIMESTAMP(6) WITHOUT TIME ZONE
          )
          """);
    }
  }

  private static void prepareJsonSchema(PGSimpleDataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS \"skis_types\"");
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS "skis_types"."json_types" (
            "id" BIGINT PRIMARY KEY,
            "jsonValue" JSON,
            "jsonbValue" JSONB
          )
          """);
    }
  }

  private static void insertJson(
      PGSimpleDataSource dataSource, long id, String json, String jsonb) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO \"skis_types\".\"json_types\" "
                    + "(\"id\", \"jsonValue\", \"jsonbValue\") VALUES (?, ?, ?)")) {
      statement.setLong(1, id);
      PostgreSqlCodecs.JSON.bind(statement, 2, json, JdbcWriteContext.EMPTY);
      PostgreSqlCodecs.JSON.bind(statement, 3, jsonb, JdbcWriteContext.EMPTY);
      statement.executeUpdate();
    }
  }

  private static void deleteRows(
      PGSimpleDataSource dataSource, String table, long... identifiers) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "DELETE FROM \"skis_types\".\"" + table + "\" WHERE \"id\" = ?")) {
      for (long id : identifiers) {
        statement.setLong(1, id);
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private static long positiveRandomId() {
    return (UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE) % (Long.MAX_VALUE - 2);
  }

  private static long nextId(long id) {
    return id + 1;
  }

  private static String environmentOrDefault(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
