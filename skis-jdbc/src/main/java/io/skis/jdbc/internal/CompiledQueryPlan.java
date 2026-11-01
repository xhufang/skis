package io.skis.jdbc.internal;

import io.skis.dialect.spi.CursorReadSupport;
import io.skis.dialect.spi.RenderedSql;
import io.skis.mapping.spi.ParameterBinder;
import io.skis.mapping.spi.RowDecoder;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable JDBC query plan with a value-independent SQL template and generated mapping code. */
public final class CompiledQueryPlan<R extends @Nullable Object, P> {

  private final String dialectId;
  private final RenderedSql renderedSql;
  private final ParameterBinder<P> parameterBinder;
  private final RowDecoder<R> rowDecoder;
  private final CursorReadSupport cursorReadSupport;
  private final boolean singleStatement;

  /** Resolves the single-statement property once, outside the execution/cache-hit path. */
  public CompiledQueryPlan(
      String dialectId,
      RenderedSql renderedSql,
      ParameterBinder<P> parameterBinder,
      RowDecoder<R> rowDecoder,
      CursorReadSupport cursorReadSupport) {
    this.dialectId = requireDialectText(dialectId);
    this.renderedSql = Objects.requireNonNull(renderedSql, "renderedSql");
    this.parameterBinder = Objects.requireNonNull(parameterBinder, "parameterBinder");
    this.rowDecoder = Objects.requireNonNull(rowDecoder, "rowDecoder");
    this.cursorReadSupport = Objects.requireNonNull(cursorReadSupport, "cursorReadSupport");
    this.singleStatement =
        cursorReadSupport.defaultFetchSize() > 0 && CursorSql.isSingleStatement(renderedSql.sql());
  }

  public String dialectId() {
    return dialectId;
  }

  public RenderedSql renderedSql() {
    return renderedSql;
  }

  public ParameterBinder<P> parameterBinder() {
    return parameterBinder;
  }

  public RowDecoder<R> rowDecoder() {
    return rowDecoder;
  }

  public CursorReadSupport cursorReadSupport() {
    return cursorReadSupport;
  }

  public boolean singleStatement() {
    return singleStatement;
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
