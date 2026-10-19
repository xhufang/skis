package io.skis.sql.ast;

import java.util.Objects;

/**
 * Explicit one-based reference to a visible output position of the current query block.
 *
 * <p>The node carries the selected expression's type and effective nullability for validation, but
 * rendering only serializes the position and never evaluates the selected expression a second time.
 * Framework lowering creates this node when an ORDER BY expression can reuse a DISTINCT selection.
 * The controlled compilation pipeline rejects it in every other expression position.
 *
 * @param <T> Java representation of the selected SQL value
 */
public record SelectedOutputReference<T>(
    int position, Class<T> javaType, SqlType sqlType, Nullability nullability)
    implements SqlExpression<T> {

  /** Validates the output position and declared value descriptor. */
  public SelectedOutputReference {
    if (position < 1) {
      throw new IllegalArgumentException("selected output position must be one-based");
    }
    Objects.requireNonNull(javaType, "javaType");
    Objects.requireNonNull(sqlType, "sqlType");
    Objects.requireNonNull(nullability, "nullability");
    if (javaType == void.class || javaType == Void.class) {
      throw new IllegalArgumentException("selected output Java type must not be void");
    }
    if (nullability.isNullable() && javaType.isPrimitive()) {
      throw new IllegalArgumentException(
          "a nullable selected output reference cannot use a primitive Java type");
    }
  }

  @Override
  public boolean nullable() {
    return nullability.isNullable();
  }

  /** Creates a reference whose descriptor was copied from the selected expression. */
  public static <T> SelectedOutputReference<T> of(int position, SqlExpression<T> selected) {
    Objects.requireNonNull(selected, "selected");
    return new SelectedOutputReference<>(
        position, selected.javaType(), selected.sqlType(), selected.nullability());
  }
}
