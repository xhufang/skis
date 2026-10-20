package io.skis.dialect.spi;

import io.skis.sql.ast.QueryBlockPath;
import io.skis.sql.ast.QueryClause;
import io.skis.sql.ast.SqlExpression;
import java.util.Objects;

/**
 * One framework-issued expression occurrence in a resolved statement.
 *
 * <p>The same AST node can occur at several structural positions; each position receives its own
 * occurrence. Instances are only produced together with the owning {@link ResolvedStatement}, so a
 * caller cannot pair an arbitrary node with an arbitrary analysis result.
 */
public final class Occurrence {

  private final Object owner;
  private final QueryBlockPath blockPath;
  private final QueryClause clause;
  private final int itemOrdinal;
  private final SqlExpression<?> expression;
  private final ExpressionInfo info;

  Occurrence(
      Object owner,
      QueryBlockPath blockPath,
      QueryClause clause,
      int itemOrdinal,
      SqlExpression<?> expression,
      ExpressionInfo info) {
    this.owner = Objects.requireNonNull(owner, "owner");
    this.blockPath = Objects.requireNonNull(blockPath, "blockPath");
    this.clause = Objects.requireNonNull(clause, "clause");
    this.itemOrdinal = itemOrdinal;
    this.expression = Objects.requireNonNull(expression, "expression");
    this.info = Objects.requireNonNull(info, "info");
    if (itemOrdinal < 0) {
      throw new IllegalArgumentException("itemOrdinal must not be negative");
    }
  }

  /** Stable path of the query block that owns this occurrence. */
  public QueryBlockPath blockPath() {
    return blockPath;
  }

  /** Clause that owns this expression. */
  public QueryClause clause() {
    return clause;
  }

  /** Zero-based item position inside the owning clause. */
  public int itemOrdinal() {
    return itemOrdinal;
  }

  /** The immutable AST node at this occurrence. */
  public SqlExpression<?> expression() {
    return expression;
  }

  /** Restricted resolved information for this occurrence. */
  public ExpressionInfo info() {
    return info;
  }

  /** Whether this occurrence belongs to a nested query block. */
  public boolean nested() {
    return !blockPath.locations().isEmpty();
  }

  Object owner() {
    return owner;
  }

  @Override
  public String toString() {
    return "Occurrence[" + blockPath + ' ' + clause.displayName() + " item #" + itemOrdinal + ']';
  }
}
