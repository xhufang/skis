package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Immutable single-table SELECT with explicit ordering, hidden items, and pagination. */
public final class SelectStatement implements StatementAst {

  private final boolean distinct;
  private final List<SqlExpression<?>> selections;
  private final List<HiddenSelection> hiddenSelections;
  private final TableExpression<?> from;
  private final @Nullable SqlPredicate where;
  private final List<OrderByItem> orderBy;
  private final @Nullable SelectPagination pagination;

  /** Creates a complete immutable SELECT tree. */
  public SelectStatement(
      boolean distinct,
      List<? extends SqlExpression<?>> selections,
      List<HiddenSelection> hiddenSelections,
      TableExpression<?> from,
      @Nullable SqlPredicate where,
      List<OrderByItem> orderBy,
      @Nullable SelectPagination pagination) {
    this.distinct = distinct;
    Objects.requireNonNull(selections, "selections");
    this.selections = List.copyOf(selections);
    if (this.selections.isEmpty()) {
      throw new IllegalArgumentException("SELECT requires at least one expression");
    }
    this.hiddenSelections =
        List.copyOf(Objects.requireNonNull(hiddenSelections, "hiddenSelections"));
    this.from = Objects.requireNonNull(from, "from");
    this.where = where;
    this.orderBy = List.copyOf(Objects.requireNonNull(orderBy, "orderBy"));
    this.pagination = pagination;
    SemanticValidator.validate(this);
  }

  /** Creates a single-table SELECT statement. */
  public SelectStatement(
      List<? extends SqlExpression<?>> selections,
      TableExpression<?> from,
      @Nullable SqlPredicate where) {
    this(false, selections, List.of(), from, where, List.of(), null);
  }

  /** Creates a SELECT without a WHERE clause. */
  public SelectStatement(List<? extends SqlExpression<?>> selections, TableExpression<?> from) {
    this(selections, from, null);
  }

  public List<SqlExpression<?>> selections() {
    return selections;
  }

  public boolean distinct() {
    return distinct;
  }

  public List<HiddenSelection> hiddenSelections() {
    return hiddenSelections;
  }

  public TableExpression<?> from() {
    return from;
  }

  public Optional<SqlPredicate> where() {
    return Optional.ofNullable(where);
  }

  public List<OrderByItem> orderBy() {
    return orderBy;
  }

  public Optional<SelectPagination> pagination() {
    return Optional.ofNullable(pagination);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof SelectStatement statement
            && distinct == statement.distinct
            && selections.equals(statement.selections)
            && hiddenSelections.equals(statement.hiddenSelections)
            && from.equals(statement.from)
            && Objects.equals(where, statement.where)
            && orderBy.equals(statement.orderBy)
            && Objects.equals(pagination, statement.pagination);
  }

  @Override
  public int hashCode() {
    int result = Boolean.hashCode(distinct);
    result = 31 * result + selections.hashCode();
    result = 31 * result + hiddenSelections.hashCode();
    result = 31 * result + from.hashCode();
    result = 31 * result + Objects.hashCode(where);
    result = 31 * result + orderBy.hashCode();
    return 31 * result + Objects.hashCode(pagination);
  }
}
