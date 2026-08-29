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
            "primitive_boolean" BOOLEAN NOT NULL,
            "nullable_boolean" BOOLEAN,
            "primitive_byte" TINYINT NOT NULL,
            "nullable_byte" TINYINT,
            "primitive_short" SMALLINT NOT NULL,
            "nullable_short" SMALLINT,
            "primitive_integer" INTEGER NOT NULL,
            "nullable_integer" INTEGER,
            "primitive_long" BIGINT NOT NULL,
            "nullable_long" BIGINT,
            "primitive_float" REAL NOT NULL,
            "nullable_float" REAL,
            "primitive_double" DOUBLE PRECISION NOT NULL,
            "nullable_double" DOUBLE PRECISION,
            "primitive_character" CHARACTER(1) NOT NULL,
            "nullable_character" CHARACTER(1),
            "required_string" VARCHAR(200) NOT NULL,
            "nullable_string" VARCHAR(200),
            "big_integer_value" NUMERIC(40, 0),
            "big_decimal_value" NUMERIC(30, 6),
            "bytes_value" VARBINARY,
            "uuid_value" UUID,
            "instant_value" TIMESTAMP(6) WITH TIME ZONE,
            "local_date_value" DATE,
            "local_time_value" TIME(6),
            "local_date_time_value" TIMESTAMP(6),
            "offset_time_value" TIME(6) WITH TIME ZONE,
            "offset_date_time_value" TIMESTAMP(6) WITH TIME ZONE,
            "sql_date_value" DATE,
            "sql_time_value" TIME,
            "sql_timestamp_value" TIMESTAMP(6)
          )
          """);
    }
    return dataSource;
  }
}
