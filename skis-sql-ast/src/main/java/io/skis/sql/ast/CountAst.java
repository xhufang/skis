package io.skis.sql.ast;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Independent single-table count plan; ordering and pagination never belong to this node. */
public final class CountAst implements StatementAst {

  private final TableExpression<?> source;
  private final @Nullable SqlPredicate predicate;
  private final @Nullable SqlExpression<?> distinctExpression;

  /**
   * Creates {@code COUNT(*)} when {@code distinctExpression} is null, otherwise counts the distinct
   * result values, including one result for {@code NULL} when the expression is nullable.
   */
  public CountAst(
      TableExpression<?> source,
      @Nullable SqlPredicate predicate,
      @Nullable SqlExpression<?> distinctExpression) {
    this.source = Objects.requireNonNull(source, "source");
    this.predicate = predicate;
    this.distinctExpression = distinctExpression;
    SemanticValidator.validate(this);
  }

  public TableExpression<?> source() {
    return source;
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
            && source.equals(count.source)
            && Objects.equals(predicate, count.predicate)
            && Objects.equals(distinctExpression, count.distinctExpression);
  }

  @Override
  public int hashCode() {
    return Objects.hash(source, predicate, distinctExpression);
  }
}
