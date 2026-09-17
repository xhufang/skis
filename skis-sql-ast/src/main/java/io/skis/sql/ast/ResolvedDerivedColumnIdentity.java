package io.skis.sql.ast;

import java.util.Objects;

/** Value-independent identity and descriptor of one resolved derived output reference. */
public record ResolvedDerivedColumnIdentity(
    ResolvedSourceIdentity source,
    int outputOrdinal,
    String outputName,
    String javaTypeName,
    SqlType sqlType)
    implements ResolvedColumnReference {

  public ResolvedDerivedColumnIdentity {
    Objects.requireNonNull(source, "source");
    if (outputOrdinal < 0) {
      throw new IllegalArgumentException("outputOrdinal must not be negative");
    }
    requireText(outputName, "outputName");
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
