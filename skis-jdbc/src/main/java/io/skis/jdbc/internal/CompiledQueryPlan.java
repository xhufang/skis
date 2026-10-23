package io.skis.jdbc.internal;

import io.skis.dialect.spi.RenderedSql;
import io.skis.mapping.spi.ParameterBinder;
import io.skis.mapping.spi.RowDecoder;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable JDBC query plan with a value-independent SQL template and generated mapping code. */
public record CompiledQueryPlan<R extends @Nullable Object, P>(
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
      throw new IllegalArgumentException("dialectId must not be blank");
    }
    return value;
  }
}
