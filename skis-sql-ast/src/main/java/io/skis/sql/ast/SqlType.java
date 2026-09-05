package io.skis.sql.ast;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Objects;
import java.util.UUID;

/** Portable SQL value type used for expression validation before dialect rendering. */
public enum SqlType {
  BOOLEAN(Family.BOOLEAN),
  TINYINT(Family.NUMERIC),
  SMALLINT(Family.NUMERIC),
  INTEGER(Family.NUMERIC),
  BIGINT(Family.NUMERIC),
  REAL(Family.NUMERIC),
  DOUBLE(Family.NUMERIC),
  DECIMAL(Family.NUMERIC),
  CHARACTER(Family.CHARACTER),
  VARCHAR(Family.CHARACTER),
  VARBINARY(Family.BINARY),
  UUID(Family.UUID),
  DATE(Family.DATE),
  TIME(Family.TIME),
  TIME_WITH_TIME_ZONE(Family.TIME),
  TIMESTAMP(Family.TIMESTAMP),
  TIMESTAMP_WITH_TIME_ZONE(Family.TIMESTAMP),
  OTHER(Family.OTHER);

  private final Family family;

  SqlType(Family family) {
    this.family = family;
  }

  /** Resolves the portable SQL type for a currently supported Java representation. */
  public static SqlType fromJavaType(Class<?> javaType) {
    Class<?> type = boxed(Objects.requireNonNull(javaType, "javaType"));
    if (type == Boolean.class) {
      return BOOLEAN;
    }
    if (type == Byte.class) {
      return TINYINT;
    }
    if (type == Short.class) {
      return SMALLINT;
    }
    if (type == Integer.class) {
      return INTEGER;
    }
    if (type == Long.class) {
      return BIGINT;
    }
    if (type == Float.class) {
      return REAL;
    }
    if (type == Double.class) {
      return DOUBLE;
    }
    if (type == BigInteger.class || type == BigDecimal.class) {
      return DECIMAL;
    }
    if (type == Character.class) {
      return CHARACTER;
    }
    if (type == String.class) {
      return VARCHAR;
    }
    if (type == byte[].class) {
      return VARBINARY;
    }
    if (type == UUID.class) {
      return UUID;
    }
    if (type == LocalDate.class || type == Date.class) {
      return DATE;
    }
    if (type == LocalTime.class || type == Time.class) {
      return TIME;
    }
    if (type == OffsetTime.class) {
      return TIME_WITH_TIME_ZONE;
    }
    if (type == LocalDateTime.class || type == Timestamp.class) {
      return TIMESTAMP;
    }
    if (type == Instant.class || type == OffsetDateTime.class) {
      return TIMESTAMP_WITH_TIME_ZONE;
    }
    return OTHER;
  }

  /** Whether two values may participate in equality or set-membership comparison. */
  public boolean equalityCompatibleWith(SqlType other) {
    Objects.requireNonNull(other, "other");
    if (this == OTHER || other == OTHER) {
      return false;
    }
    return family == other.family;
  }

  /** Whether two values may participate in ordered comparison or {@code BETWEEN}. */
  public boolean orderingCompatibleWith(SqlType other) {
    return equalityCompatibleWith(other) && isOrderable();
  }

  /** Whether this value family supports portable SQL {@code LIKE}. */
  public boolean supportsLike() {
    return family == Family.CHARACTER;
  }

  /** Whether this value belongs to the portable numeric family. */
  public boolean isNumeric() {
    return family == Family.NUMERIC;
  }

  /** Whether this value belongs to the portable character family. */
  public boolean isCharacter() {
    return family == Family.CHARACTER;
  }

  /** Whether this type can be named by the portable {@code CAST} expression subset. */
  public boolean isCastTarget() {
    return this != VARBINARY && this != OTHER;
  }

  /** Whether the initial portable subset permits a cast from this type to {@code target}. */
  public boolean castableTo(SqlType target) {
    Objects.requireNonNull(target, "target");
    if (!isCastTarget() || !target.isCastTarget()) {
      return false;
    }
    if (equalityCompatibleWith(target)) {
      return true;
    }
    if (isCharacter() || target.isCharacter()) {
      return true;
    }
    return (this == DATE && target.isTimestamp()) || (target == DATE && isTimestamp());
  }

  /** Whether this value family has a portable ordering for the initial DSL. */
  public boolean isOrderable() {
    return switch (family) {
      case NUMERIC, CHARACTER, DATE, TIME, TIMESTAMP -> true;
      default -> false;
    };
  }

  private boolean isTimestamp() {
    return family == Family.TIMESTAMP;
  }

  private static Class<?> boxed(Class<?> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    return switch (type.getName()) {
      case "boolean" -> Boolean.class;
      case "byte" -> Byte.class;
      case "short" -> Short.class;
      case "int" -> Integer.class;
      case "long" -> Long.class;
      case "float" -> Float.class;
      case "double" -> Double.class;
      case "char" -> Character.class;
      default -> type;
    };
  }

  private enum Family {
    BOOLEAN,
    NUMERIC,
    CHARACTER,
    BINARY,
    UUID,
    DATE,
    TIME,
    TIMESTAMP,
    OTHER
  }
}
