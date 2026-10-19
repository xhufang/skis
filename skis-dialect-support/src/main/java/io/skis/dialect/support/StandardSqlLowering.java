package io.skis.dialect.support;

import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectLowering;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.sql.ast.StatementAst;
import java.util.Objects;

/**
 * Reusable dialect-lowering stage for standard SQL dialects.
 *
 * <p>Portable structural normalization is a mandatory stage owned by the controlled pipeline after
 * dialect-specific lowering. This implementation therefore returns the exact input statement and
 * lets the pipeline perform that traversal once before post-validation and layout.
 */
public final class StandardSqlLowering implements DialectLowering {

  private final DialectCapabilities capabilities;

  private StandardSqlLowering(DialectCapabilities capabilities) {
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
  }

  /** Creates a lowering bound to the dialect's immutable capability set. */
  public static StandardSqlLowering of(DialectCapabilities capabilities) {
    return new StandardSqlLowering(capabilities);
  }

  @Override
  public StatementAst lower(ResolvedStatement input) {
    Objects.requireNonNull(input, "input");
    return input.statement();
  }

  @Override
  public String toString() {
    return "StandardSqlLowering" + capabilities;
  }
}
