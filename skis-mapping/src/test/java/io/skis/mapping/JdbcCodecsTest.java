package io.skis.mapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcCodecsTest {

  @Test
  void readsAndBindsPrimitiveNumericAndBooleanValues() throws Exception {
    assertRead(
        true,
        true,
        "getBoolean",
        new Object[] {1},
        resultSet -> JdbcCodecs.readBoolean(resultSet, 1, JdbcReadContext.EMPTY));
    assertRead(
        (byte) 2,
        (byte) 2,
        "getByte",
        new Object[] {2},
        resultSet -> JdbcCodecs.readByte(resultSet, 2, JdbcReadContext.EMPTY));
    assertRead(
        (short) 3,
        (short) 3,
        "getShort",
        new Object[] {3},
        resultSet -> JdbcCodecs.readShort(resultSet, 3, JdbcReadContext.EMPTY));
    assertRead(
        4,
        4,
        "getInt",
        new Object[] {4},
        resultSet -> JdbcCodecs.readInt(resultSet, 4, JdbcReadContext.EMPTY));
    assertRead(
        5L,
        5L,
        "getLong",
        new Object[] {5},
        resultSet -> JdbcCodecs.readLong(resultSet, 5, JdbcReadContext.EMPTY));
    assertRead(
        6.25F,
        6.25F,
        "getFloat",
        new Object[] {6},
        resultSet -> JdbcCodecs.readFloat(resultSet, 6, JdbcReadContext.EMPTY));
    assertRead(
        7.5D,
        7.5D,
        "getDouble",
        new Object[] {7},
        resultSet -> JdbcCodecs.readDouble(resultSet, 7, JdbcReadContext.EMPTY));

    assertBind(
        "setBoolean",
        new Object[] {1, true},
        statement -> JdbcCodecs.bindBoolean(statement, 1, true, JdbcWriteContext.EMPTY));
    assertBind(
        "setByte",
        new Object[] {2, (byte) 2},
        statement -> JdbcCodecs.bindByte(statement, 2, (byte) 2, JdbcWriteContext.EMPTY));
    assertBind(
        "setShort",
        new Object[] {3, (short) 3},
        statement -> JdbcCodecs.bindShort(statement, 3, (short) 3, JdbcWriteContext.EMPTY));
    assertBind(
        "setInt",
        new Object[] {4, 4},
        statement -> JdbcCodecs.bindInt(statement, 4, 4, JdbcWriteContext.EMPTY));
    assertBind(
        "setLong",
        new Object[] {5, 5L},
        statement -> JdbcCodecs.bindLong(statement, 5, 5L, JdbcWriteContext.EMPTY));
    assertBind(
        "setFloat",
        new Object[] {6, 6.25F},
        statement -> JdbcCodecs.bindFloat(statement, 6, 6.25F, JdbcWriteContext.EMPTY));
    assertBind(
        "setDouble",
        new Object[] {7, 7.5D},
        statement -> JdbcCodecs.bindDouble(statement, 7, 7.5D, JdbcWriteContext.EMPTY));
  }

  @Test
  void distinguishesNullableReadsFromRequiredReadAndBindValues() throws Exception {
    InvocationRecorder nullableInteger = new InvocationRecorder(0, true);
    assertNull(
        JdbcCodecs.readNullableInt(
            nullableInteger.proxy(ResultSet.class), 3, JdbcReadContext.EMPTY));
    nullableInteger.assertInvocation("getInt", new Object[] {3});

    InvocationRecorder missingPrimitive = new InvocationRecorder(0L, true);
    SQLException primitiveFailure =
        assertThrows(
            SQLException.class,
            () ->
                JdbcCodecs.readLong(
                    missingPrimitive.proxy(ResultSet.class), 4, JdbcReadContext.EMPTY));
    assertEquals(
        "non-nullable column at JDBC index 4 returned NULL", primitiveFailure.getMessage());

    assertEquals("value", JdbcCodecs.requireReadValue("value", 5));
    SQLException readFailure =
        assertThrows(SQLException.class, () -> JdbcCodecs.requireReadValue(null, 5));
    assertEquals("non-nullable column at JDBC index 5 returned NULL", readFailure.getMessage());

    assertEquals("value", JdbcCodecs.requireBindValue("value", 6));
    SQLException bindFailure =
        assertThrows(SQLException.class, () -> JdbcCodecs.requireBindValue(null, 6));
    assertEquals("non-nullable parameter at JDBC index 6 is null", bindFailure.getMessage());
  }

  @Test
  void readsUuidAndJavaTimeValuesThroughDedicatedJdbcMappings() throws Exception {
    UUID uuid = UUID.fromString("1e2228a0-4e3e-4ae2-8cf0-a21c9db5999d");
    assertRead(
        uuid,
        uuid,
        "getObject",
        new Object[] {3, UUID.class},
        resultSet -> JdbcCodecs.readUuid(resultSet, 3, JdbcReadContext.EMPTY));

    Instant instant = Instant.parse("2026-08-20T03:04:05.123456Z");
    OffsetDateTime instantJdbcValue = instant.atOffset(ZoneOffset.ofHours(8));
    assertRead(
        instant,
        instantJdbcValue,
        "getObject",
        new Object[] {4, OffsetDateTime.class},
        resultSet -> JdbcCodecs.readInstant(resultSet, 4, JdbcReadContext.EMPTY));

    LocalDate localDate = LocalDate.of(2026, 8, 20);
    assertRead(
        localDate,
        java.sql.Date.valueOf(localDate),
        "getDate",
        new Object[] {5},
        resultSet -> JdbcCodecs.readLocalDate(resultSet, 5, JdbcReadContext.EMPTY));

    LocalTime localTime = LocalTime.of(11, 22, 33, 456_789_000);
    assertRead(
        localTime,
        localTime,
        "getObject",
        new Object[] {6, LocalTime.class},
        resultSet -> JdbcCodecs.readLocalTime(resultSet, 6, JdbcReadContext.EMPTY));

    LocalDateTime localDateTime = LocalDateTime.of(localDate, localTime);
    assertRead(
        localDateTime,
        java.sql.Timestamp.valueOf(localDateTime),
        "getTimestamp",
        new Object[] {7},
        resultSet -> JdbcCodecs.readLocalDateTime(resultSet, 7, JdbcReadContext.EMPTY));

    OffsetTime offsetTime = localTime.atOffset(ZoneOffset.ofHours(8));
    assertRead(
        offsetTime,
        offsetTime,
        "getObject",
        new Object[] {8, OffsetTime.class},
        resultSet -> JdbcCodecs.readOffsetTime(resultSet, 8, JdbcReadContext.EMPTY));

    OffsetDateTime offsetDateTime = localDateTime.atOffset(ZoneOffset.ofHours(8));
    assertRead(
        offsetDateTime,
        offsetDateTime,
        "getObject",
        new Object[] {9, OffsetDateTime.class},
        resultSet -> JdbcCodecs.readOffsetDateTime(resultSet, 9, JdbcReadContext.EMPTY));
  }

  @Test
  void readsCharacterAndExactBigIntegerValues() throws Exception {
    assertRead(
        'S',
        "S",
        "getString",
        new Object[] {2},
        resultSet -> JdbcCodecs.readChar(resultSet, 2, JdbcReadContext.EMPTY));
    assertRead(
        new BigInteger("123456789012345678901234567890"),
        new BigDecimal("123456789012345678901234567890"),
        "getBigDecimal",
        new Object[] {3},
        resultSet -> JdbcCodecs.readBigInteger(resultSet, 3, JdbcReadContext.EMPTY));

    InvocationRecorder fractional = new InvocationRecorder(new BigDecimal("1.5"));
    assertThrows(
        SQLException.class,
        () ->
            JdbcCodecs.readBigInteger(fractional.proxy(ResultSet.class), 3, JdbcReadContext.EMPTY));
    InvocationRecorder multipleCharacters = new InvocationRecorder("SKIS");
    assertThrows(
        SQLException.class,
        () ->
            JdbcCodecs.readChar(
                multipleCharacters.proxy(ResultSet.class), 2, JdbcReadContext.EMPTY));
  }

  @Test
  void bindsUuidAndJavaTimeValuesWithExplicitJdbcTypes() throws Exception {
    UUID uuid = UUID.fromString("1e2228a0-4e3e-4ae2-8cf0-a21c9db5999d");
    assertBind(
        "setObject",
        new Object[] {3, uuid, Types.OTHER},
        statement -> JdbcCodecs.bindUuid(statement, 3, uuid, JdbcWriteContext.EMPTY));

    Instant instant = Instant.parse("2026-08-20T03:04:05.123456Z");
    assertBind(
        "setObject",
        new Object[] {4, instant.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE},
        statement -> JdbcCodecs.bindInstant(statement, 4, instant, JdbcWriteContext.EMPTY));

    LocalDate localDate = LocalDate.of(2026, 8, 20);
    assertBind(
        "setDate",
        new Object[] {5, java.sql.Date.valueOf(localDate)},
        statement -> JdbcCodecs.bindLocalDate(statement, 5, localDate, JdbcWriteContext.EMPTY));

    LocalTime localTime = LocalTime.of(11, 22, 33, 456_789_000);
    assertBind(
        "setObject",
        new Object[] {6, localTime, Types.TIME},
        statement -> JdbcCodecs.bindLocalTime(statement, 6, localTime, JdbcWriteContext.EMPTY));

    LocalDateTime localDateTime = LocalDateTime.of(localDate, localTime);
    assertBind(
        "setTimestamp",
        new Object[] {7, java.sql.Timestamp.valueOf(localDateTime)},
        statement ->
            JdbcCodecs.bindLocalDateTime(statement, 7, localDateTime, JdbcWriteContext.EMPTY));

    OffsetTime offsetTime = localTime.atOffset(ZoneOffset.ofHours(8));
    assertBind(
        "setObject",
        new Object[] {8, offsetTime, Types.TIME_WITH_TIMEZONE},
        statement -> JdbcCodecs.bindOffsetTime(statement, 8, offsetTime, JdbcWriteContext.EMPTY));

    OffsetDateTime offsetDateTime = localDateTime.atOffset(ZoneOffset.ofHours(8));
    assertBind(
        "setObject",
        new Object[] {9, offsetDateTime, Types.TIMESTAMP_WITH_TIMEZONE},
        statement ->
            JdbcCodecs.bindOffsetDateTime(statement, 9, offsetDateTime, JdbcWriteContext.EMPTY));
  }

  @Test
  void bindsNullValuesWithAConcreteJdbcType() throws Exception {
    assertBind(
        "setNull",
        new Object[] {1, Types.BOOLEAN},
        statement -> JdbcCodecs.bindNullableBoolean(statement, 1, null, JdbcWriteContext.EMPTY));
    assertBind(
        "setNull",
        new Object[] {1, Types.TINYINT},
        statement -> JdbcCodecs.bindNullableByte(statement, 1, null, JdbcWriteContext.EMPTY));
    assertBind(
        "setNull",
        new Object[] {1, Types.SMALLINT},
        statement -> JdbcCodecs.bindNullableShort(statement, 1, null, JdbcWriteContext.EMPTY));
    assertBind(
        "setNull",
        new Object[] {1, Types.INTEGER},
        statement -> JdbcCodecs.bindNullableInt(statement, 1, null, JdbcWriteContext.EMPTY));
    assertBind(
        "setNull",
        new Object[] {1, Types.BIGINT},
        statement -> JdbcCodecs.bindNullableLong(statement, 1, null, JdbcWriteContext.EMPTY));
    assertBind(
        "setNull",
        new Object[] {1, Types.REAL},
        statement -> JdbcCodecs.bindNullableFloat(statement, 1, null, JdbcWriteContext.EMPTY));
    assertBind(
        "setNull",
        new Object[] {1, Types.DOUBLE},
        statement -> JdbcCodecs.bindNullableDouble(statement, 1, null, JdbcWriteContext.EMPTY));
    assertBind(
        "setNull",
        new Object[] {1, Types.CHAR},
        statement -> JdbcCodecs.bindNullableChar(statement, 1, null, JdbcWriteContext.EMPTY));
    assertBind(
        "setNull",
        new Object[] {3, Types.OTHER},
        statement -> JdbcCodecs.bindUuid(statement, 3, null, JdbcWriteContext.EMPTY));
    assertBind(
        "setNull",
        new Object[] {4, Types.TIMESTAMP_WITH_TIMEZONE},
        statement -> JdbcCodecs.bindInstant(statement, 4, null, JdbcWriteContext.EMPTY));
    assertBind(
        "setNull",
        new Object[] {5, Types.DATE},
        statement -> JdbcCodecs.bindLocalDate(statement, 5, null, JdbcWriteContext.EMPTY));
  }

  @Test
  void readsAndBindsLegacySqlTemporalValues() throws Exception {
    java.sql.Date date = java.sql.Date.valueOf("2026-08-20");
    java.sql.Time time = java.sql.Time.valueOf("11:22:33");
    java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf("2026-08-20 11:22:33.123456789");

    assertRead(
        date,
        date,
        "getDate",
        new Object[] {1},
        resultSet -> JdbcCodecs.readSqlDate(resultSet, 1, JdbcReadContext.EMPTY));
    assertRead(
        time,
        time,
        "getTime",
        new Object[] {2},
        resultSet -> JdbcCodecs.readSqlTime(resultSet, 2, JdbcReadContext.EMPTY));
    assertRead(
        timestamp,
        timestamp,
        "getTimestamp",
        new Object[] {3},
        resultSet -> JdbcCodecs.readSqlTimestamp(resultSet, 3, JdbcReadContext.EMPTY));

    assertBind(
        "setDate",
        new Object[] {1, date},
        statement -> JdbcCodecs.bindSqlDate(statement, 1, date, JdbcWriteContext.EMPTY));
    assertBind(
        "setTime",
        new Object[] {2, time},
        statement -> JdbcCodecs.bindSqlTime(statement, 2, time, JdbcWriteContext.EMPTY));
    assertBind(
        "setTimestamp",
        new Object[] {3, timestamp},
        statement -> JdbcCodecs.bindSqlTimestamp(statement, 3, timestamp, JdbcWriteContext.EMPTY));
  }

  @Test
  void bindsCharacterAndBigIntegerThroughConcreteJdbcMethods() throws Exception {
    assertBind(
        "setString",
        new Object[] {1, "S"},
        statement -> JdbcCodecs.bindChar(statement, 1, 'S', JdbcWriteContext.EMPTY));
    BigInteger value = new BigInteger("123456789012345678901234567890");
    assertBind(
        "setBigDecimal",
        new Object[] {2, new BigDecimal(value)},
        statement -> JdbcCodecs.bindBigInteger(statement, 2, value, JdbcWriteContext.EMPTY));
  }

  private static <T> void assertRead(
      T expected,
      Object jdbcValue,
      String expectedMethod,
      Object[] expectedArguments,
      JdbcReader<T> reader)
      throws Exception {
    InvocationRecorder recorder = new InvocationRecorder(jdbcValue);
    assertEquals(expected, reader.read(recorder.proxy(ResultSet.class)));
    recorder.assertInvocation(expectedMethod, expectedArguments);
  }

  private static void assertBind(
      String expectedMethod, Object[] expectedArguments, JdbcBinder binder) throws Exception {
    InvocationRecorder recorder = new InvocationRecorder(null);
    binder.bind(recorder.proxy(PreparedStatement.class));
    recorder.assertInvocation(expectedMethod, expectedArguments);
  }

  @FunctionalInterface
  private interface JdbcReader<T> {
    T read(ResultSet resultSet) throws SQLException;
  }

  @FunctionalInterface
  private interface JdbcBinder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  private static final class InvocationRecorder implements InvocationHandler {
    private final Object result;
    private final boolean wasNull;
    private Method invokedMethod;
    private Object[] invokedArguments;

    private InvocationRecorder(Object result) {
      this(result, result == null);
    }

    private InvocationRecorder(Object result, boolean wasNull) {
      this.result = result;
      this.wasNull = wasNull;
    }

    private <T> T proxy(Class<T> interfaceType) {
      return interfaceType.cast(
          Proxy.newProxyInstance(
              JdbcCodecsTest.class.getClassLoader(), new Class<?>[] {interfaceType}, this));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) {
      if (method.getDeclaringClass() == Object.class) {
        return switch (method.getName()) {
          case "equals" -> proxy == arguments[0];
          case "hashCode" -> System.identityHashCode(proxy);
          case "toString" -> "JDBC test proxy";
          default -> throw new AssertionError("unexpected Object method " + method);
        };
      }
      if (method.getName().equals("wasNull")) {
        return wasNull;
      }
      invokedMethod = method;
      invokedArguments = arguments == null ? new Object[0] : arguments.clone();
      return result;
    }

    private void assertInvocation(String expectedMethod, Object[] expectedArguments) {
      assertEquals(expectedMethod, invokedMethod.getName());
      assertArrayEquals(expectedArguments, invokedArguments);
    }
  }
}
