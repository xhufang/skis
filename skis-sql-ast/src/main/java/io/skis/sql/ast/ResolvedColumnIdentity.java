package io.skis.sql.ast;

import java.util.Objects;

/** Value-independent identity and descriptor of one resolved physical column reference. */
public record ResolvedColumnIdentity(
    ResolvedSourceIdentity source,
    int propertyOrdinal,
    String propertyName,
    String columnName,
    String javaTypeName,
    SqlType sqlType) {

  public ResolvedColumnIdentity {
    Objects.requireNonNull(source, "source");
    if (propertyOrdinal < 0) {
      throw new IllegalArgumentException("propertyOrdinal must not be negative");
    }
    requireText(propertyName, "propertyName");
    requireText(columnName, "columnName");
    requireText(javaTypeName, "javaTypeName");
    Objects.requireNonNull(sqlType, "sqlType");
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
