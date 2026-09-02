package io.skis.metadata;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Defines how mutation plans initialize and advance a version property. */
public enum VersionStrategy {
  /** Initializes an exact numeric counter to zero and advances it by one. */
  NUMERIC_INCREMENT {
    @Override
    boolean unSupportsJavaType(Class<?> javaType) {
      Class<?> boxedType = boxed(javaType);
      return boxedType != Byte.class
          && boxedType != Short.class
          && boxedType != Integer.class
          && boxedType != Long.class
          && boxedType != BigInteger.class
          && boxedType != BigDecimal.class;
    }

    @Override
    boolean requiresInsertableColumn() {
      return true;
    }

    @Override
    boolean requiresUpdatableColumn() {
      return true;
    }
  };

  abstract boolean unSupportsJavaType(Class<?> javaType);

  /** Uses a supplied initial version or creates the strategy's type-correct default value. */
  public final <V> V initialize(Class<V> javaType, @Nullable V suppliedValue) {
    Class<?> boxedType = boxed(Objects.requireNonNull(javaType, "javaType"));
    if (unSupportsJavaType(boxedType)) {
      throw new IllegalArgumentException(
          "version strategy '"
              + this
              + "' does not support Java type '"
              + javaType.getTypeName()
              + "'");
    }
    if (suppliedValue != null) {
      if (!boxedType.isInstance(suppliedValue)) {
        throw new IllegalArgumentException(
            "version value requires "
                + boxedType.getTypeName()
                + " but received "
                + suppliedValue.getClass().getTypeName());
      }
      return suppliedValue;
    }
    return cast(zero(boxedType));
  }

  /** Calculates the next in-memory version value using the same semantics as generated SQL. */
  public final <V> V advance(Class<V> javaType, V currentValue) {
    Class<?> boxedType = boxed(Objects.requireNonNull(javaType, "javaType"));
    Object current = Objects.requireNonNull(currentValue, "currentValue");
    if (unSupportsJavaType(boxedType) || !boxedType.isInstance(current)) {
      throw new IllegalArgumentException(
          "version value is incompatible with strategy '" + this + "'");
    }
    return cast(increment(boxedType, current));
  }

  boolean requiresInsertableColumn() {
    return false;
  }

  boolean requiresUpdatableColumn() {
    return false;
  }

  private static Object zero(Class<?> javaType) {
    if (javaType == Byte.class) {
      return (byte) 0;
    }
    if (javaType == Short.class) {
      return (short) 0;
    }
    if (javaType == Integer.class) {
      return 0;
    }
    if (javaType == Long.class) {
      return 0L;
    }
    if (javaType == BigInteger.class) {
      return BigInteger.ZERO;
    }
    if (javaType == BigDecimal.class) {
      return BigDecimal.ZERO;
    }
    throw new IllegalArgumentException(
        "unsupported numeric version type " + javaType.getTypeName());
  }

  private static Object increment(Class<?> javaType, Object current) {
    if (javaType == Byte.class) {
      byte value = (Byte) current;
      if (value == Byte.MAX_VALUE) {
        throw new ArithmeticException("byte version overflow");
      }
      return (byte) (value + 1);
    }
    if (javaType == Short.class) {
      short value = (Short) current;
      if (value == Short.MAX_VALUE) {
        throw new ArithmeticException("short version overflow");
      }
      return (short) (value + 1);
    }
    if (javaType == Integer.class) {
      return Math.addExact((Integer) current, 1);
    }
    if (javaType == Long.class) {
      return Math.addExact((Long) current, 1L);
    }
    if (javaType == BigInteger.class) {
      return ((BigInteger) current).add(BigInteger.ONE);
    }
    if (javaType == BigDecimal.class) {
      return ((BigDecimal) current).add(BigDecimal.ONE);
    }
    throw new IllegalArgumentException(
        "unsupported numeric version type " + javaType.getTypeName());
  }

  @SuppressWarnings("unchecked")
  private static <V> V cast(Object value) {
    return (V) value;
  }

  private static Class<?> boxed(Class<?> javaType) {
    if (!javaType.isPrimitive()) {
      return javaType;
    }
    if (javaType == byte.class) {
      return Byte.class;
    }
    if (javaType == short.class) {
      return Short.class;
    }
    if (javaType == int.class) {
      return Integer.class;
    }
    if (javaType == long.class) {
      return Long.class;
    }
    if (javaType == float.class) {
      return Float.class;
    }
    if (javaType == double.class) {
      return Double.class;
    }
    return javaType;
  }
}
