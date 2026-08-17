package io.skis.metadata;

import java.util.Objects;

/** Immutable physical column mapping for a persistent property. */
public record ColumnMeta(
    String name,
    boolean nullable,
    boolean insertable,
    boolean updatable,
    int length,
    int precision,
    int scale,
    String comment) {

  /** Validates and creates column metadata. */
  public ColumnMeta {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(comment, "comment");
    if (name.isBlank()) {
      throw new IllegalArgumentException("column name must not be blank");
    }
    if (length < 0) {
      throw new IllegalArgumentException("column length must not be negative");
    }
    if (precision < 0) {
      throw new IllegalArgumentException("column precision must not be negative");
    }
    if (scale < 0) {
      throw new IllegalArgumentException("column scale must not be negative");
    }
    if (precision > 0 && scale > precision) {
      throw new IllegalArgumentException("column scale must not exceed precision");
    }
  }

  /** Creates a column mapping with the annotation defaults. */
  public static ColumnMeta of(String name, boolean nullable) {
    return new ColumnMeta(name, nullable, true, true, 255, 0, 0, "");
  }
}
