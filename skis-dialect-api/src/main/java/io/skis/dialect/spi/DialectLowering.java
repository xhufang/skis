package io.skis.dialect.spi;

import io.skis.sql.ast.StatementAst;

/**
 * Converts one already analyzed and capacity-validated statement into the structure a dialect can
 * render directly.
 *
 * <p>Implementations receive a target-independent {@link ResolvedStatement}, return a new immutable
 * structure or the exact input statement, and must not read parameter values, open a connection, or
 * render SQL text. Returning the exact input instance is the identity result; returning any other
 * object is treated as a new structure and is semantically reanalyzed before final layout.
 *
 * <p>Implementations must preserve the statement kind and, for SELECT statements, the DISTINCT flag
 * and ordered visible/hidden output lists. Rewriting clauses outside that public result contract is
 * permitted and is checked again before rendering.
 */
@FunctionalInterface
public interface DialectLowering {

  /** Lowers the statement or returns the exact input instance when no conversion is required. */
  StatementAst lower(ResolvedStatement input);

  /** Returns the identity lowering used by dialects that need no structural conversion. */
  static DialectLowering identity() {
    return new DialectLowering() {
      @Override
      public StatementAst lower(ResolvedStatement input) {
        return input.statement();
      }

      @Override
      public String toString() {
        return "DialectLowering.identity";
      }
    };
  }
}
