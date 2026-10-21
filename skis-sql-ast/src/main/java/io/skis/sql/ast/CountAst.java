package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Independent count plan; ordering and pagination never belong to this node.
 *
 * <p>Construction validates local expression invariants. Complete scope, parameter, and
 * nested-block validation occurs at the semantic-analysis or renderer boundary.
 */
public final class CountAst implements StatementAst {

  private final FromClause fromClause;
  private final @Nullable SqlPredicate predicate;
  private final @Nullable SqlExpression<?> distinctExpression;
  private final boolean nullCompensated;

  /**
   * Creates {@code COUNT(*)} when {@code distinctExpression} is null, otherwise counts the distinct
   * result values without NULL compensation.
   *
   * <p>A nullable distinct expression that must count a NULL result uses the four-argument
   * constructor with {@code nullCompensated=true}. The framework normalization stage decides that
   * flag from resolved effective nullability before final parameter layout; renderers only
   * serialize the explicit structure.
   */
  public CountAst(
      FromClause fromClause,
      @Nullable SqlPredicate predicate,
      @Nullable SqlExpression<?> distinctExpression) {
    this(fromClause, predicate, distinctExpression, false);
  }

  /**
   * Creates an independent count plan with explicit NULL-result compensation.
   *
   * <p>When {@code nullCompensated} is true, {@code distinctExpression} must be present and the
   * final structure counts one extra row when the distinct set contains NULL.
   */
  public CountAst(
      FromClause fromClause,
      @Nullable SqlPredicate predicate,
      @Nullable SqlExpression<?> distinctExpression,
      boolean nullCompensated) {
    this.fromClause = Objects.requireNonNull(fromClause, "fromClause");
    this.predicate = predicate;
    this.distinctExpression = distinctExpression;
    this.nullCompensated = nullCompensated;
    if (nullCompensated && distinctExpression == null) {
      throw new IllegalArgumentException("NULL compensation requires a distinct count expression");
    }
    SemanticValidator.validateLocal(this);
  }

  /** Creates a single-table count plan. */
  public CountAst(
      TableExpression<?> source,
      @Nullable SqlPredicate predicate,
      @Nullable SqlExpression<?> distinctExpression) {
    this(FromClause.of(source), predicate, distinctExpression);
  }

  /** Creates a single-table count plan with explicit NULL-result compensation. */
  public CountAst(
      TableExpression<?> source,
      @Nullable SqlPredicate predicate,
      @Nullable SqlExpression<?> distinctExpression,
      boolean nullCompensated) {
    this(FromClause.of(source), predicate, distinctExpression, nullCompensated);
  }

  public FromClause fromClause() {
    return fromClause;
  }

  /** Returns the root table for source compatibility with the single-table AST. */
  public TableExpression<?> source() {
    return fromClause
        .root()
        .entityTable()
        .orElseThrow(() -> new IllegalStateException("COUNT root is not an entity table"));
  }

  /** Returns the ordered joins shared with the content query. */
  public List<JoinClause> joins() {
    return fromClause.joins();
  }

  public Optional<SqlPredicate> predicate() {
    return Optional.ofNullable(predicate);
  }

  public Optional<SqlExpression<?>> distinctExpression() {
    return Optional.ofNullable(distinctExpression);
  }

  /**
   * Whether the final structure must compensate for one NULL among the distinct results.
   *
   * <p>The portable normalization stage sets this flag before final layout. The rendered form is
   * {@code COUNT(DISTINCT e) + CASE WHEN COUNT(*) > COUNT(e) THEN 1 ELSE 0 END}.
   */
  public boolean nullCompensated() {
    return nullCompensated;
  }

  @Override
  public boolean equals(@org.jspecify.annotations.Nullable Object other) {
    return this == other
        || other instanceof CountAst count
            && nullCompensated == count.nullCompensated
            && fromClause.equals(count.fromClause)
            && Objects.equals(predicate, count.predicate)
            && Objects.equals(distinctExpression, count.distinctExpression);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fromClause, predicate, distinctExpression, nullCompensated);
  }
}
