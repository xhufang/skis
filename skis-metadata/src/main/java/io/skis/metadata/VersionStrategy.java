package io.skis.metadata;

/** Defines how mutation plans initialize and advance a version property. */
public enum VersionStrategy {
  /** Initializes a numeric version to zero and advances it by one. */
  NUMERIC_INCREMENT {
    @Override
    boolean supportsJavaType(Class<?> javaType) {
      return Number.class.isAssignableFrom(boxed(javaType));
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

  abstract boolean supportsJavaType(Class<?> javaType);

  boolean requiresInsertableColumn() {
    return false;
  }

  boolean requiresUpdatableColumn() {
    return false;
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
