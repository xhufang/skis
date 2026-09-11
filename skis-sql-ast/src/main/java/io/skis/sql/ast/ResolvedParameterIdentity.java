package io.skis.sql.ast;

import java.util.Objects;

/** Value-independent identity and descriptor of one parameter slot as seen in a query block. */
public record ResolvedParameterIdentity(
    QueryBlockPath blockPath,
    int parameterOrdinal,
    String javaTypeName,
    SqlType sqlType,
    Nullability nullability) {

  public ResolvedParameterIdentity {
    Objects.requireNonNull(blockPath, "blockPath");
    if (parameterOrdinal < 0) {
      throw new IllegalArgumentException("parameterOrdinal must not be negative");
    }
    Objects.requireNonNull(javaTypeName, "javaTypeName");
    if (javaTypeName.isBlank()) {
      throw new IllegalArgumentException("javaTypeName must not be blank");
    }
    Objects.requireNonNull(sqlType, "sqlType");
    Objects.requireNonNull(nullability, "nullability");
  }
}
