package io.skis.sql.ast;

import java.util.Objects;

/** Immutable descriptor for one visible output column of a derived relation. */
public record DerivedOutputColumn(
    Identifier name, Class<?> javaType, SqlType sqlType, Nullability nullability) {

  public DerivedOutputColumn {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(javaType, "javaType");
    Objects.requireNonNull(sqlType, "sqlType");
    Objects.requireNonNull(nullability, "nullability");
    if (javaType == void.class || javaType == Void.class) {
      throw new IllegalArgumentException("derived output Java type must not be void");
    }
    if (nullability.isNullable() && javaType.isPrimitive()) {
      throw new IllegalArgumentException("nullable derived output must use a boxed Java type");
    }
  }
}
