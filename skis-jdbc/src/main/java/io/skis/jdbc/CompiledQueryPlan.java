package io.skis.jdbc;

import io.skis.dialect.RenderedSql;
import io.skis.mapping.ParameterBinder;
import io.skis.mapping.RowDecoder;
import java.util.Objects;

/** Immutable JDBC query plan with a value-independent SQL template and generated mapping code. */
public record CompiledQueryPlan<R, P>(
    String dialectId,
    RenderedSql renderedSql,
    ParameterBinder<P> parameterBinder,
    RowDecoder<R> rowDecoder) {

  /** Creates a query plan whose binder must consume every rendered placeholder exactly once. */
  public CompiledQueryPlan(
      String dialectId,
      RenderedSql renderedSql,
      ParameterBinder<P> parameterBinder,
      RowDecoder<R> rowDecoder) {
    this.dialectId = requireDialectText(dialectId);
    this.renderedSql = Objects.requireNonNull(renderedSql, "renderedSql");
    this.parameterBinder = Objects.requireNonNull(parameterBinder, "parameterBinder");
    this.rowDecoder = Objects.requireNonNull(rowDecoder, "rowDecoder");
  }

  public String sql() {
    return renderedSql.sql();
  }

  public int parameterCount() {
    return renderedSql.parameterCount();
  }

  private static String requireDialectText(String value) {
    Objects.requireNonNull(value, "dialectId");
    if (value.isBlank()) {
      throw new IllegalArgumentException("dialectId" + " must not be blank");
    }
    return value;
  }
}
