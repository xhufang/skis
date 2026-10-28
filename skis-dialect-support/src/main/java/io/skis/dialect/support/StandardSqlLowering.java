package io.skis.dialect.support;

import io.skis.dialect.spi.DialectCapabilities;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.DialectLowering;
import io.skis.dialect.spi.PortableLowering;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.ReturningStatement;
import io.skis.sql.ast.StatementAst;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Reusable dialect-lowering stage for standard SQL dialects.
 *
 * <p>Portable structural normalization is a mandatory stage owned by the controlled pipeline after
 * dialect-specific lowering. The default composition returns the exact input statement; the
 * built-in profiles first apply required expression/aggregate conversions. Portable normalization
 * remains owned by the pipeline before post-validation and layout.
 */
public final class StandardSqlLowering implements DialectLowering {

  private final DialectCapabilities capabilities;
  private final PortableLowering.@Nullable AggregateTarget aggregateTarget;

  private StandardSqlLowering(DialectCapabilities capabilities) {
    this(capabilities, null);
  }

  private StandardSqlLowering(
      DialectCapabilities capabilities,
      PortableLowering.@Nullable AggregateTarget aggregateTarget) {
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.aggregateTarget = aggregateTarget;
  }

  /** Creates a lowering bound to the dialect's immutable capability set. */
  public static StandardSqlLowering of(DialectCapabilities capabilities) {
    return new StandardSqlLowering(capabilities);
  }

  /** PostgreSQL's fixed aggregate input widening plus resolved grouping normalization. */
  public static StandardSqlLowering postgresql(DialectCapabilities capabilities) {
    return new StandardSqlLowering(capabilities, PortableLowering.AggregateTarget.POSTGRESQL);
  }

  /** H2's exact AVG inputs and floating aggregate result casts, plus grouping normalization. */
  public static StandardSqlLowering h2(DialectCapabilities capabilities) {
    return new StandardSqlLowering(capabilities, PortableLowering.AggregateTarget.H2);
  }

  @Override
  public StatementAst lower(ResolvedStatement input) {
    Objects.requireNonNull(input, "input");
    StatementAst lowered =
        aggregateTarget == null
            ? input.statement()
            : PortableLowering.lowerAggregates(input, aggregateTarget);
    if (lowered instanceof ReturningStatement returning) {
      ReturningStatement.Form form =
          capabilities.supports(DialectFeature.MUTATION_RESULT_TABLE)
              ? returning.mutation() instanceof DeleteStatement
                  ? ReturningStatement.Form.OLD_TABLE
                  : ReturningStatement.Form.FINAL_TABLE
              : ReturningStatement.Form.NATIVE;
      return returning.form() == form
          ? returning
          : new ReturningStatement(returning.mutation(), returning.selections(), form);
    }
    return lowered;
  }

  @Override
  public String toString() {
    return "StandardSqlLowering" + capabilities;
  }
}
