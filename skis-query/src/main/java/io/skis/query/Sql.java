package io.skis.query;

import io.skis.sql.ast.Nullability;
import java.util.Objects;

/** Static construction entry point for reusable SQL query descriptions and their inputs. */
public final class Sql {

  private Sql() {}

  /**
   * Creates a nullable query-level parameter reference with no diagnostic name.
   *
   * <p>The reference contains no value or ordinal. Bind its value separately through {@link
   * QueryParameters}.
   */
  public static <V> QueryParameter<V> parameter(Class<V> javaType) {
    return new QueryParameter<>(
        Objects.requireNonNull(javaType, "javaType"), Nullability.NULLABLE, null);
  }

  /** Creates a nullable parameter reference whose name is used only in diagnostics. */
  public static <V> QueryParameter<V> parameter(Class<V> javaType, String diagnosticName) {
    return new QueryParameter<>(
        Objects.requireNonNull(javaType, "javaType"),
        Nullability.NULLABLE,
        Objects.requireNonNull(diagnosticName, "diagnosticName"));
  }
}
