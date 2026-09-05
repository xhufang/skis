package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Independent count plan; ordering and pagination never belong to this node. */
public final class CountAst implements StatementAst {

  private final FromClause fromClause;
  private final @Nullable SqlPredicate predicate;
  private final @Nullable SqlExpression<?> distinctExpression;

  /**
   * Creates {@code COUNT(*)} when {@code distinctExpression} is null, otherwise counts the distinct
   * result values. Renderers use the final FROM/JOIN effective nullability to include one result
   * for {@code NULL} when necessary.
   */
  public CountAst(
      FromClause fromClause,
      @Nullable SqlPredicate predicate,
      @Nullable SqlExpression<?> distinctExpression) {
    this.fromClause = Objects.requireNonNull(fromClause, "fromClause");
    this.predicate = predicate;
    this.distinctExpression = distinctExpression;
    SemanticValidator.validate(this);
  }

  /** Creates a single-table count plan. */
  public CountAst(
      TableExpression<?> source,
      @Nullable SqlPredicate predicate,
      @Nullable SqlExpression<?> distinctExpression) {
    this(FromClause.of(source), predicate, distinctExpression);
  }

  public FromClause fromClause() {
    return fromClause;
  }

  /** Returns the root table for source compatibility with the single-table AST. */
  public TableExpression<?> source() {
    return fromClause.root();
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

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof CountAst count
            && fromClause.equals(count.fromClause)
            && Objects.equals(predicate, count.predicate)
            && Objects.equals(distinctExpression, count.distinctExpression);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fromClause, predicate, distinctExpression);
  }
}
