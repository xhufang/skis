package io.skis.mapping;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Built-in codecs and allocation-free static entry points used by generated code. */
public final class JdbcCodecs {

  public static final JdbcTypeCodec<Boolean> BOOLEAN =
      codec(JdbcCodecs::readNullableBoolean, JdbcCodecs::bindNullableBoolean);
  public static final JdbcTypeCodec<Byte> BYTE =
      codec(JdbcCodecs::readNullableByte, JdbcCodecs::bindNullableByte);
  public static final JdbcTypeCodec<Short> SHORT =
      codec(JdbcCodecs::readNullableShort, JdbcCodecs::bindNullableShort);
  public static final JdbcTypeCodec<Integer> INTEGER =
      codec(JdbcCodecs::readNullableInt, JdbcCodecs::bindNullableInt);
  public static final JdbcTypeCodec<Long> LONG =
      codec(JdbcCodecs::readNullableLong, JdbcCodecs::bindNullableLong);
  public static final JdbcTypeCodec<Float> FLOAT =
      codec(JdbcCodecs::readNullableFloat, JdbcCodecs::bindNullableFloat);
  public static final JdbcTypeCodec<Double> DOUBLE =
      codec(JdbcCodecs::readNullableDouble, JdbcCodecs::bindNullableDouble);
  public static final JdbcTypeCodec<Character> CHARACTER =
      codec(JdbcCodecs::readNullableChar, JdbcCodecs::bindNullableChar);
  public static final JdbcTypeCodec<String> STRING =
      codec(JdbcCodecs::readString, JdbcCodecs::bindString);
  public static final JdbcTypeCodec<BigInteger> BIG_INTEGER =
      codec(JdbcCodecs::readBigInteger, JdbcCodecs::bindBigInteger);
  public static final JdbcTypeCodec<BigDecimal> BIG_DECIMAL =
      codec(JdbcCodecs::readBigDecimal, JdbcCodecs::bindBigDecimal);
  public static final JdbcTypeCodec<byte[]> BYTES =
      codec(JdbcCodecs::readBytes, JdbcCodecs::bindBytes);
  public static final JdbcTypeCodec<UUID> UUID = codec(JdbcCodecs::readUuid, JdbcCodecs::bindUuid);
  public static final JdbcTypeCodec<Instant> INSTANT =
      codec(JdbcCodecs::readInstant, JdbcCodecs::bindInstant);
  public static final JdbcTypeCodec<LocalDate> LOCAL_DATE =
      codec(JdbcCodecs::readLocalDate, JdbcCodecs::bindLocalDate);
  public static final JdbcTypeCodec<LocalTime> LOCAL_TIME =
      codec(JdbcCodecs::readLocalTime, JdbcCodecs::bindLocalTime);
  public static final JdbcTypeCodec<LocalDateTime> LOCAL_DATE_TIME =
      codec(JdbcCodecs::readLocalDateTime, JdbcCodecs::bindLocalDateTime);
  public static final JdbcTypeCodec<OffsetTime> OFFSET_TIME =
      codec(JdbcCodecs::readOffsetTime, JdbcCodecs::bindOffsetTime);
  public static final JdbcTypeCodec<OffsetDateTime> OFFSET_DATE_TIME =
      codec(JdbcCodecs::readOffsetDateTime, JdbcCodecs::bindOffsetDateTime);
  public static final JdbcTypeCodec<Date> SQL_DATE =
      codec(JdbcCodecs::readSqlDate, JdbcCodecs::bindSqlDate);
  public static final JdbcTypeCodec<Time> SQL_TIME =
      codec(JdbcCodecs::readSqlTime, JdbcCodecs::bindSqlTime);
  public static final JdbcTypeCodec<Timestamp> SQL_TIMESTAMP =
      codec(JdbcCodecs::readSqlTimestamp, JdbcCodecs::bindSqlTimestamp);

  private JdbcCodecs() {}

  public static boolean readBoolean(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    boolean value = resultSet.getBoolean(index);
    requirePresent(resultSet, index);
    return value;
  }

  public static @Nullable Boolean readNullableBoolean(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    boolean value = resultSet.getBoolean(index);
    return resultSet.wasNull() ? null : value;
  }

  public static byte readByte(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    byte value = resultSet.getByte(index);
    requirePresent(resultSet, index);
    return value;
  }

  public static @Nullable Byte readNullableByte(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    byte value = resultSet.getByte(index);
    return resultSet.wasNull() ? null : value;
  }

  public static short readShort(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    short value = resultSet.getShort(index);
    requirePresent(resultSet, index);
    return value;
  }

  public static @Nullable Short readNullableShort(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    short value = resultSet.getShort(index);
    return resultSet.wasNull() ? null : value;
  }

  public static int readInt(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    int value = resultSet.getInt(index);
    requirePresent(resultSet, index);
    return value;
  }

  public static @Nullable Integer readNullableInt(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    int value = resultSet.getInt(index);
    return resultSet.wasNull() ? null : value;
  }

  public static long readLong(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    long value = resultSet.getLong(index);
    requirePresent(resultSet, index);
    return value;
  }

  public static @Nullable Long readNullableLong(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    long value = resultSet.getLong(index);
    return resultSet.wasNull() ? null : value;
  }

  public static float readFloat(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    float value = resultSet.getFloat(index);
    requirePresent(resultSet, index);
    return value;
  }

  public static @Nullable Float readNullableFloat(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    float value = resultSet.getFloat(index);
    return resultSet.wasNull() ? null : value;
  }

  public static double readDouble(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    double value = resultSet.getDouble(index);
    requirePresent(resultSet, index);
    return value;
  }

  public static @Nullable Double readNullableDouble(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    double value = resultSet.getDouble(index);
    return resultSet.wasNull() ? null : value;
  }

  public static char readChar(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    String value = resultSet.getString(index);
    if (value == null) {
      throw new SQLException("non-nullable column at JDBC index " + index + " returned NULL");
    }
    return requireSingleCharacter(value, index);
  }

  public static @Nullable Character readNullableChar(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    String value = resultSet.getString(index);
    return value == null ? null : requireSingleCharacter(value, index);
  }

  public static @Nullable String readString(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    return resultSet.getString(index);
  }

  public static @Nullable BigInteger readBigInteger(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    BigDecimal value = resultSet.getBigDecimal(index);
    if (value == null) {
      return null;
    }
    try {
      return value.toBigIntegerExact();
    } catch (ArithmeticException exception) {
      throw new SQLException(
          "numeric value at JDBC index " + index + " is not an exact integer", exception);
    }
  }

  public static @Nullable BigDecimal readBigDecimal(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    return resultSet.getBigDecimal(index);
  }

  public static byte @Nullable [] readBytes(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    return resultSet.getBytes(index);
  }

  public static @Nullable UUID readUuid(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    UUID value = resultSet.getObject(index, UUID.class);
    return resultSet.wasNull() ? null : value;
  }

  public static @Nullable Instant readInstant(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    OffsetDateTime value = resultSet.getObject(index, OffsetDateTime.class);
    return resultSet.wasNull() ? null : value.toInstant();
  }

  public static @Nullable LocalDate readLocalDate(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    Date value = resultSet.getDate(index);
    return value == null ? null : value.toLocalDate();
  }

  public static @Nullable LocalTime readLocalTime(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    LocalTime value = resultSet.getObject(index, LocalTime.class);
    return resultSet.wasNull() ? null : value;
  }

  public static @Nullable LocalDateTime readLocalDateTime(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    Timestamp value = resultSet.getTimestamp(index);
    return value == null ? null : value.toLocalDateTime();
  }

  public static @Nullable OffsetTime readOffsetTime(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    OffsetTime value = resultSet.getObject(index, OffsetTime.class);
    return resultSet.wasNull() ? null : value;
  }

  public static @Nullable OffsetDateTime readOffsetDateTime(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    OffsetDateTime value = resultSet.getObject(index, OffsetDateTime.class);
    return resultSet.wasNull() ? null : value;
  }

  public static @Nullable Date readSqlDate(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    return resultSet.getDate(index);
  }

  public static @Nullable Time readSqlTime(ResultSet resultSet, int index, JdbcReadContext context)
      throws SQLException {
    return resultSet.getTime(index);
  }

  public static @Nullable Timestamp readSqlTimestamp(
      ResultSet resultSet, int index, JdbcReadContext context) throws SQLException {
    return resultSet.getTimestamp(index);
  }

  /** Requires a non-null value read for a non-nullable column. */
  public static <T> T requireReadValue(@Nullable T value, int index) throws SQLException {
    if (value == null) {
      throw new SQLException("non-nullable column at JDBC index " + index + " returned NULL");
    }
    return value;
  }

  /** Requires a non-null value before binding a non-nullable parameter. */
  public static <T> T requireBindValue(@Nullable T value, int index) throws SQLException {
    if (value == null) {
      throw new SQLException("non-nullable parameter at JDBC index " + index + " is null");
    }
    return value;
  }

  public static void bindBoolean(
      PreparedStatement statement, int index, boolean value, JdbcWriteContext context)
      throws SQLException {
    statement.setBoolean(index, value);
  }

  public static void bindNullableBoolean(
      PreparedStatement statement, int index, @Nullable Boolean value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.BOOLEAN);
    } else {
      statement.setBoolean(index, value);
    }
  }

  public static void bindByte(
      PreparedStatement statement, int index, byte value, JdbcWriteContext context)
      throws SQLException {
    statement.setByte(index, value);
  }

  public static void bindNullableByte(
      PreparedStatement statement, int index, @Nullable Byte value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.TINYINT);
    } else {
      statement.setByte(index, value);
    }
  }

  public static void bindShort(
      PreparedStatement statement, int index, short value, JdbcWriteContext context)
      throws SQLException {
    statement.setShort(index, value);
  }

  public static void bindNullableShort(
      PreparedStatement statement, int index, @Nullable Short value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.SMALLINT);
    } else {
      statement.setShort(index, value);
    }
  }

  public static void bindInt(
      PreparedStatement statement, int index, int value, JdbcWriteContext context)
      throws SQLException {
    statement.setInt(index, value);
  }

  public static void bindNullableInt(
      PreparedStatement statement, int index, @Nullable Integer value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.INTEGER);
    } else {
      statement.setInt(index, value);
    }
  }

  public static void bindLong(
      PreparedStatement statement, int index, long value, JdbcWriteContext context)
      throws SQLException {
    statement.setLong(index, value);
  }

  public static void bindNullableLong(
      PreparedStatement statement, int index, @Nullable Long value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.BIGINT);
    } else {
      statement.setLong(index, value);
    }
  }

  public static void bindFloat(
      PreparedStatement statement, int index, float value, JdbcWriteContext context)
      throws SQLException {
    statement.setFloat(index, value);
  }

  public static void bindNullableFloat(
      PreparedStatement statement, int index, @Nullable Float value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.REAL);
    } else {
      statement.setFloat(index, value);
    }
  }

  public static void bindDouble(
      PreparedStatement statement, int index, double value, JdbcWriteContext context)
      throws SQLException {
    statement.setDouble(index, value);
  }

  public static void bindNullableDouble(
      PreparedStatement statement, int index, @Nullable Double value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.DOUBLE);
    } else {
      statement.setDouble(index, value);
    }
  }

  public static void bindChar(
      PreparedStatement statement, int index, char value, JdbcWriteContext context)
      throws SQLException {
    statement.setString(index, String.valueOf(value));
  }

  public static void bindNullableChar(
      PreparedStatement statement, int index, @Nullable Character value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.CHAR);
    } else {
      statement.setString(index, String.valueOf(value));
    }
  }

  public static void bindString(
      PreparedStatement statement, int index, @Nullable String value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.VARCHAR);
    } else {
      statement.setString(index, value);
    }
  }

  public static void bindBigInteger(
      PreparedStatement statement, int index, @Nullable BigInteger value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.NUMERIC);
    } else {
      statement.setBigDecimal(index, new BigDecimal(value));
    }
  }

  public static void bindBigDecimal(
      PreparedStatement statement, int index, @Nullable BigDecimal value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.DECIMAL);
    } else {
      statement.setBigDecimal(index, value);
    }
  }

  public static void bindBytes(
      PreparedStatement statement, int index, byte @Nullable [] value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.VARBINARY);
    } else {
      statement.setBytes(index, value);
    }
  }

  public static void bindUuid(
      PreparedStatement statement, int index, @Nullable UUID value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.OTHER);
    } else {
      statement.setObject(index, value, Types.OTHER);
    }
  }

  public static void bindInstant(
      PreparedStatement statement, int index, @Nullable Instant value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
    } else {
      statement.setObject(index, value.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    }
  }

  public static void bindLocalDate(
      PreparedStatement statement, int index, @Nullable LocalDate value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.DATE);
    } else {
      statement.setDate(index, Date.valueOf(value));
    }
  }

  public static void bindLocalTime(
      PreparedStatement statement, int index, @Nullable LocalTime value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.TIME);
    } else {
      statement.setObject(index, value, Types.TIME);
    }
  }

  public static void bindLocalDateTime(
      PreparedStatement statement,
      int index,
      @Nullable LocalDateTime value,
      JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.TIMESTAMP);
    } else {
      statement.setTimestamp(index, Timestamp.valueOf(value));
    }
  }

  public static void bindOffsetTime(
      PreparedStatement statement, int index, @Nullable OffsetTime value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.TIME_WITH_TIMEZONE);
    } else {
      statement.setObject(index, value, Types.TIME_WITH_TIMEZONE);
    }
  }

  public static void bindOffsetDateTime(
      PreparedStatement statement,
      int index,
      @Nullable OffsetDateTime value,
      JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
    } else {
      statement.setObject(index, value, Types.TIMESTAMP_WITH_TIMEZONE);
    }
  }

  public static void bindSqlDate(
      PreparedStatement statement, int index, @Nullable Date value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.DATE);
    } else {
      statement.setDate(index, value);
    }
  }

  public static void bindSqlTime(
      PreparedStatement statement, int index, @Nullable Time value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.TIME);
    } else {
      statement.setTime(index, value);
    }
  }

  public static void bindSqlTimestamp(
      PreparedStatement statement, int index, @Nullable Timestamp value, JdbcWriteContext context)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.TIMESTAMP);
    } else {
      statement.setTimestamp(index, value);
    }
  }

  private static void requirePresent(ResultSet resultSet, int index) throws SQLException {
    if (resultSet.wasNull()) {
      throw new SQLException("non-nullable column at JDBC index " + index + " returned NULL");
    }
  }

  private static char requireSingleCharacter(String value, int index) throws SQLException {
    if (value.length() != 1) {
      throw new SQLException(
          "character column at JDBC index "
              + index
              + " returned "
              + value.length()
              + " characters");
    }
    return value.charAt(0);
  }

  private static <T> JdbcTypeCodec<T> codec(Reader<T> reader, Binder<T> binder) {
    return new JdbcTypeCodec<>() {
      @Override
      public @Nullable T read(ResultSet resultSet, int index, JdbcReadContext context)
          throws SQLException {
        return reader.read(resultSet, index, context);
      }

      @Override
      public void bind(
          PreparedStatement statement, int index, @Nullable T value, JdbcWriteContext context)
          throws SQLException {
        binder.bind(statement, index, value, context);
      }
    };
  }

  @FunctionalInterface
  private interface Reader<T> {
    @Nullable T read(ResultSet resultSet, int index, JdbcReadContext context) throws SQLException;
  }

  @FunctionalInterface
  private interface Binder<T> {
    void bind(PreparedStatement statement, int index, @Nullable T value, JdbcWriteContext context)
        throws SQLException;
  }
}
