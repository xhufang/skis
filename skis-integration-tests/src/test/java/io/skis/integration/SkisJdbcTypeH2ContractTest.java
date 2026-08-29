package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.skis.dialect.h2.H2Dialect;
import io.skis.runtime.SkisExecutor;
import io.skis.runtime.SkisExecutorFactory;
import io.skis.testmodel.types.JdbcTypes;
import io.skis.testmodel.types.skis.JdbcTypesMeta;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class SkisJdbcTypeH2ContractTest {

  @Test
  void roundTripsEveryDeclaredH2MappingThroughGeneratedCode() throws Exception {
    DataSource dataSource = database();
    SkisExecutor executor = SkisExecutorFactory.create(dataSource, H2Dialect.INSTANCE);
    JdbcTypes expected = JdbcTypeContractSupport.nonNullValues(1L);
    JdbcTypes nulls = JdbcTypeContractSupport.nullValues(2L);
    JdbcTypes emptyBytes = JdbcTypeContractSupport.emptyBytesValue(3L);

    assertEquals(1, executor.insert(JdbcTypesMeta.ENTITY, expected));
    assertEquals(1, executor.insert(JdbcTypesMeta.ENTITY, nulls));
    assertEquals(1, executor.insert(JdbcTypesMeta.ENTITY, emptyBytes));

    JdbcTypeContractSupport.assertNonNullValues(
        expected, executor.findById(JdbcTypesMeta.ENTITY, expected.id()).orElseThrow());
    JdbcTypeContractSupport.assertNullValues(
        executor.findById(JdbcTypesMeta.ENTITY, nulls.id()).orElseThrow());
    JdbcTypeContractSupport.assertNonNullValues(
        emptyBytes, executor.findById(JdbcTypesMeta.ENTITY, emptyBytes.id()).orElseThrow());
    JdbcTypeContractSupport.assertInvalidReadsFail(dataSource);
  }

  private static DataSource database() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:skis_jdbc_types_"
            + UUID.randomUUID().toString().replace('-', '_')
            + ";DB_CLOSE_DELAY=-1");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA \"skis_types\"");
      statement.execute(
          """
          CREATE TABLE "skis_types"."jdbc_types" (
            "id" BIGINT PRIMARY KEY,
            "primitiveBoolean" BOOLEAN NOT NULL,
            "nullableBoolean" BOOLEAN,
            "primitiveByte" TINYINT NOT NULL,
            "nullableByte" TINYINT,
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
            "bytesValue" VARBINARY,
            "uuidValue" UUID,
            "instantValue" TIMESTAMP(6) WITH TIME ZONE,
            "localDateValue" DATE,
            "localTimeValue" TIME(6),
            "localDateTimeValue" TIMESTAMP(6),
            "offsetTimeValue" TIME(6) WITH TIME ZONE,
            "offsetDateTimeValue" TIMESTAMP(6) WITH TIME ZONE,
            "sqlDateValue" DATE,
            "sqlTimeValue" TIME,
            "sqlTimestampValue" TIMESTAMP(6)
          )
          """);
    }
    return dataSource;
  }
}
