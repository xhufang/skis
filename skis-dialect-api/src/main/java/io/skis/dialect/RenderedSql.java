package io.skis.dialect;

import io.skis.sql.ast.ParameterSlot;
import java.util.List;
import java.util.Objects;

/** Immutable JDBC SQL template and parameter slots in placeholder encounter order. */
public record RenderedSql(String sql, List<ParameterSlot<?>> parameters) {

  /** Validates and defensively copies rendered output. */
  public RenderedSql {
    Objects.requireNonNull(sql, "sql");
    Objects.requireNonNull(parameters, "parameters");
    if (sql.isBlank()) {
      throw new IllegalArgumentException("rendered SQL must not be blank");
    }
    parameters = List.copyOf(parameters);
  }

  /** Number of ordered parameter slots associated with the SQL template. */
  public int parameterCount() {
    return parameters.size();
  }
}
