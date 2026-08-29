package io.skis.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skis.mapping.JdbcCodecs;
import io.skis.mapping.JdbcReadContext;
import io.skis.testmodel.types.JdbcTypes;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;

final class JdbcTypeContractSupport {

  private static final LocalDate LOCAL_DATE = LocalDate.of(2026, 8, 30);
  private static final LocalTime LOCAL_TIME = LocalTime.of(11, 22, 33, 123_456_000);
  private static final LocalDateTime LOCAL_DATE_TIME = LocalDateTime.of(LOCAL_DATE, LOCAL_TIME);
  private static final OffsetTime OFFSET_TIME = LOCAL_TIME.atOffset(ZoneOffset.ofHours(8));
  private static final OffsetDateTime OFFSET_DATE_TIME =
      LOCAL_DATE_TIME.atOffset(ZoneOffset.ofHours(8));
  private static final Instant INSTANT = Instant.parse("2026-08-30T03:22:33.123456Z");

  private JdbcTypeContractSupport() {}

  static JdbcTypes nonNullValues(long id) {
    return values(id, new byte[] {0, 1, 2, -1});
  }

  static JdbcTypes emptyBytesValue(long id) {
    return values(id, new byte[0]);
  }

  private static JdbcTypes values(long id, byte[] bytes) {
    return new JdbcTypes(
        id,
        true,
        Boolean.FALSE,
        (byte) -128,
        (byte) 127,
        Short.MIN_VALUE,
        Short.MAX_VALUE,
        Integer.MIN_VALUE,
        Integer.MAX_VALUE,
        Long.MIN_VALUE,
        Long.MAX_VALUE,
        1.5F,
        -2.25F,
        3.5D,
        -4.25D,
        'S',
        'K',
        "required",
        "nullable",
        new BigInteger("1234567890123456789012345678901234567890"),
        new BigDecimal("123456789012345678901234.123456"),
        bytes,
        UUID.fromString("1e2228a0-4e3e-4ae2-8cf0-a21c9db5999d"),
        INSTANT,
        LOCAL_DATE,
        LOCAL_TIME,
        LOCAL_DATE_TIME,
        OFFSET_TIME,
        OFFSET_DATE_TIME,
        java.sql.Date.valueOf(LOCAL_DATE),
        java.sql.Time.valueOf(LOCAL_TIME),
        Timestamp.valueOf(LOCAL_DATE_TIME));
  }

  static JdbcTypes nullValues(long id) {
    return new JdbcTypes(
        id,
        false,
        null,
        (byte) 0,
        null,
        (short) 0,
        null,
        0,
        null,
        0L,
        null,
        0F,
        null,
        0D,
        null,
        'N',
        null,
        "required-null-contract",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  static void assertNonNullValues(JdbcTypes expected, JdbcTypes actual) {
    assertEquals(expected.id(), actual.id());
    assertEquals(expected.primitiveBoolean(), actual.primitiveBoolean());
    assertEquals(expected.nullableBoolean(), actual.nullableBoolean());
    assertEquals(expected.primitiveByte(), actual.primitiveByte());
    assertEquals(expected.nullableByte(), actual.nullableByte());
    assertEquals(expected.primitiveShort(), actual.primitiveShort());
    assertEquals(expected.nullableShort(), actual.nullableShort());
    assertEquals(expected.primitiveInteger(), actual.primitiveInteger());
    assertEquals(expected.nullableInteger(), actual.nullableInteger());
    assertEquals(expected.primitiveLong(), actual.primitiveLong());
    assertEquals(expected.nullableLong(), actual.nullableLong());
    assertEquals(expected.primitiveFloat(), actual.primitiveFloat());
    assertEquals(expected.nullableFloat(), actual.nullableFloat());
    assertEquals(expected.primitiveDouble(), actual.primitiveDouble());
    assertEquals(expected.nullableDouble(), actual.nullableDouble());
    assertEquals(expected.primitiveCharacter(), actual.primitiveCharacter());
    assertEquals(expected.nullableCharacter(), actual.nullableCharacter());
    assertEquals(expected.requiredString(), actual.requiredString());
    assertEquals(expected.nullableString(), actual.nullableString());
    assertEquals(expected.bigIntegerValue(), actual.bigIntegerValue());
    assertEquals(expected.bigDecimalValue(), actual.bigDecimalValue());
    assertArrayEquals(expected.bytesValue(), actual.bytesValue());
    assertEquals(expected.uuidValue(), actual.uuidValue());
    assertEquals(expected.instantValue(), actual.instantValue());
    assertEquals(expected.localDateValue(), actual.localDateValue());
    assertEquals(expected.localTimeValue(), actual.localTimeValue());
    assertEquals(expected.localDateTimeValue(), actual.localDateTimeValue());
    assertEquals(expected.offsetTimeValue(), actual.offsetTimeValue());
    assertEquals(
        expected.offsetDateTimeValue().toInstant(), actual.offsetDateTimeValue().toInstant());
    assertEquals(expected.sqlDateValue(), actual.sqlDateValue());
    assertEquals(expected.sqlTimeValue(), actual.sqlTimeValue());
    assertEquals(expected.sqlTimestampValue(), actual.sqlTimestampValue());
  }

  static void assertNullValues(JdbcTypes actual) {
    assertNull(actual.nullableBoolean());
    assertNull(actual.nullableByte());
    assertNull(actual.nullableShort());
    assertNull(actual.nullableInteger());
    assertNull(actual.nullableLong());
    assertNull(actual.nullableFloat());
    assertNull(actual.nullableDouble());
    assertNull(actual.nullableCharacter());
    assertNull(actual.nullableString());
    assertNull(actual.bigIntegerValue());
    assertNull(actual.bigDecimalValue());
    assertNull(actual.bytesValue());
    assertNull(actual.uuidValue());
    assertNull(actual.instantValue());
    assertNull(actual.localDateValue());
    assertNull(actual.localTimeValue());
    assertNull(actual.localDateTimeValue());
    assertNull(actual.offsetTimeValue());
    assertNull(actual.offsetDateTimeValue());
    assertNull(actual.sqlDateValue());
    assertNull(actual.sqlTimeValue());
    assertNull(actual.sqlTimestampValue());
  }

  static void assertInvalidReadsFail(DataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "SELECT CAST(NULL AS BIGINT), CAST(128 AS SMALLINT), "
                    + "CAST(32768 AS INTEGER), CAST(2147483648 AS BIGINT), "
                    + "CAST(9223372036854775808 AS DECIMAL(20, 0)), "
                    + "CAST(1.5 AS DECIMAL(2, 1)), CAST('SKIS' AS VARCHAR(4))")) {
      assertTrue(resultSet.next());

      SQLException primitiveNull =
          assertThrows(
              SQLException.class, () -> JdbcCodecs.readLong(resultSet, 1, JdbcReadContext.EMPTY));
      assertEquals("non-nullable column at JDBC index 1 returned NULL", primitiveNull.getMessage());
      assertThrows(
          SQLException.class, () -> JdbcCodecs.readByte(resultSet, 2, JdbcReadContext.EMPTY));
      assertThrows(
          SQLException.class, () -> JdbcCodecs.readShort(resultSet, 3, JdbcReadContext.EMPTY));
      assertThrows(
          SQLException.class, () -> JdbcCodecs.readInt(resultSet, 4, JdbcReadContext.EMPTY));
      assertThrows(
          SQLException.class, () -> JdbcCodecs.readLong(resultSet, 5, JdbcReadContext.EMPTY));
      assertThrows(
          SQLException.class, () -> JdbcCodecs.readBigInteger(resultSet, 6, JdbcReadContext.EMPTY));
      assertThrows(
          SQLException.class, () -> JdbcCodecs.readChar(resultSet, 7, JdbcReadContext.EMPTY));
    }
  }
}
