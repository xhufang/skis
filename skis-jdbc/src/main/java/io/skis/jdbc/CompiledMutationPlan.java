package io.skis.jdbc;

import io.skis.dialect.RenderedSql;
import io.skis.mapping.ParameterBinder;
import java.util.Objects;

/** Immutable JDBC mutation plan with value-independent SQL and a generated parameter binder. */
public record CompiledMutationPlan<P>(
    String operation,
    String dialectId,
    RenderedSql renderedSql,
    ParameterBinder<P> parameterBinder) {

  /** Validates the immutable plan contract. */
  public CompiledMutationPlan {
    operation = requireText(operation, "operation");
    dialectId = requireText(dialectId, "dialectId");
    Objects.requireNonNull(renderedSql, "renderedSql");
    Objects.requireNonNull(parameterBinder, "parameterBinder");
  }

  public String sql() {
    return renderedSql.sql();
  }

  public int parameterCount() {
    return renderedSql.parameterCount();
  }

  private static String requireText(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
