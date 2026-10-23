package io.skis.sql.ast;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Local aggregate semantics independent of mapping, JDBC and dialect implementations. */
final class AggregateTypeRules {

  private AggregateTypeRules() {}

  static Nullability nullability(AggregateExpression.Kind kind) {
    return kind == AggregateExpression.Kind.COUNT ? Nullability.NON_NULL : Nullability.NULLABLE;
  }

  /** Validates a fixed factory result rather than accepting a caller-selected result type. */
  static SqlType validate(
      AggregateExpression.Kind kind,
      boolean distinct,
      @Nullable SqlExpression<?> operand,
      Class<?> resultJavaType) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(resultJavaType, "resultJavaType");
    if (distinct && (kind != AggregateExpression.Kind.COUNT || operand == null)) {
      throw new IllegalArgumentException("DISTINCT requires a single COUNT argument");
    }
    if (operand == null && kind != AggregateExpression.Kind.COUNT) {
      throw new IllegalArgumentException(kind + " requires a single scalar argument");
    }
    if (operand != null) {
      SemanticValidator.validateAggregateOperand(operand);
    }

    Class<?> requiredJavaType;
    if (kind == AggregateExpression.Kind.COUNT) {
      if (distinct) {
        requireBuiltInInput(Objects.requireNonNull(operand, "operand"), false);
      }
      requiredJavaType = Long.class;
    } else {
      SqlExpression<?> input = Objects.requireNonNull(operand, "operand");
      Class<?> inputJavaType = requireBuiltInInput(input, true);
      if (kind == AggregateExpression.Kind.SUM) {
        requiredJavaType = sumResult(inputJavaType, input.sqlType());
      } else if (kind == AggregateExpression.Kind.AVG) {
        requiredJavaType = averageResult(inputJavaType, input.sqlType());
      } else {
        requiredJavaType = inputJavaType;
      }
    }
    if (resultJavaType != requiredJavaType) {
      throw new IllegalArgumentException(
          kind + " requires result Java type " + requiredJavaType.getTypeName());
    }
    return SqlType.fromJavaType(requiredJavaType);
  }

  private static Class<?> requireBuiltInInput(SqlExpression<?> input, boolean ordered) {
    Class<?> javaType = SemanticValidator.boxedJavaType(input.javaType());
    SqlType sqlType = input.sqlType();
    // This whitelist is independent of additions to the general Java/SQL type inference rules.
    if (!isBuiltInInput(javaType, sqlType, ordered)) {
      throw new IllegalArgumentException(
          "unsupported aggregate input Java/SQL type " + javaType.getTypeName() + "/" + sqlType);
    }
    return javaType;
  }

  private static boolean isBuiltInInput(Class<?> javaType, SqlType sqlType, boolean ordered) {
    return switch (sqlType) {
      case TINYINT -> javaType == Byte.class;
      case SMALLINT -> javaType == Short.class;
      case INTEGER -> javaType == Integer.class;
      case BIGINT -> javaType == Long.class;
      case REAL -> javaType == Float.class;
      case DOUBLE -> javaType == Double.class;
      case DECIMAL -> javaType == BigInteger.class || javaType == BigDecimal.class;
      case CHARACTER -> javaType == Character.class;
      case VARCHAR -> javaType == String.class;
      case DATE -> javaType == LocalDate.class || javaType == java.sql.Date.class;
      case TIME -> javaType == LocalTime.class || javaType == java.sql.Time.class;
      case TIME_WITH_TIME_ZONE -> javaType == OffsetTime.class;
      case TIMESTAMP -> javaType == LocalDateTime.class || javaType == java.sql.Timestamp.class;
      case TIMESTAMP_WITH_TIME_ZONE ->
          javaType == Instant.class || javaType == OffsetDateTime.class;
      case BOOLEAN -> !ordered && javaType == Boolean.class;
      case UUID -> !ordered && javaType == UUID.class;
      case VARBINARY -> !ordered && javaType == byte[].class;
      case OTHER -> false;
    };
  }

  private static Class<?> sumResult(Class<?> inputJavaType, SqlType inputSqlType) {
    return switch (inputSqlType) {
      case TINYINT, SMALLINT, INTEGER -> Long.class;
      case BIGINT -> BigInteger.class;
      case DECIMAL -> inputJavaType;
      case REAL, DOUBLE -> Double.class;
      default -> throw new IllegalArgumentException("SUM requires a supported numeric input");
    };
  }

  private static Class<?> averageResult(Class<?> inputJavaType, SqlType inputSqlType) {
    return switch (inputSqlType) {
      case TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL -> BigDecimal.class;
      case REAL, DOUBLE -> Double.class;
      default ->
          throw new IllegalArgumentException(
              "AVG requires a supported numeric input, received " + inputJavaType.getTypeName());
    };
  }
}
