package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Immutable SELECT with an ordered FROM/Join clause, result items, grouping, ordering, and
 * pagination.
 */
public final class SelectStatement implements StatementAst {

  private final boolean distinct;
  private final List<SqlExpression<?>> selections;
  private final List<HiddenSelection> hiddenSelections;
  private final FromClause fromClause;
  private final @Nullable SqlPredicate where;
  private final List<SqlExpression<?>> groupBy;
  private final @Nullable SqlPredicate having;
  private final List<OrderByItem> orderBy;
  private final @Nullable SelectPagination pagination;

  /** Creates a complete immutable SELECT tree, including reserved grouping structure. */
  public SelectStatement(
      boolean distinct,
      List<? extends SqlExpression<?>> selections,
      List<HiddenSelection> hiddenSelections,
      FromClause fromClause,
      @Nullable SqlPredicate where,
      List<? extends SqlExpression<?>> groupBy,
      @Nullable SqlPredicate having,
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
    this.fromClause = Objects.requireNonNull(fromClause, "fromClause");
    this.where = where;
    this.groupBy = List.copyOf(Objects.requireNonNull(groupBy, "groupBy"));
    this.having = having;
    this.orderBy = List.copyOf(Objects.requireNonNull(orderBy, "orderBy"));
    this.pagination = pagination;
    SemanticValidator.validateLocal(this);
  }

  /**
   * Creates a complete immutable SELECT tree without grouping, preserving the pre-0.2.5 AST
   * construction entry point.
   */
  public SelectStatement(
      boolean distinct,
      List<? extends SqlExpression<?>> selections,
      List<HiddenSelection> hiddenSelections,
      FromClause fromClause,
      @Nullable SqlPredicate where,
      List<OrderByItem> orderBy,
      @Nullable SelectPagination pagination) {
    this(
        distinct,
        selections,
        hiddenSelections,
        fromClause,
        where,
        List.of(),
        null,
        orderBy,
        pagination);
  }

  /** Creates a complete single-table SELECT tree, including reserved grouping structure. */
  public SelectStatement(
      boolean distinct,
      List<? extends SqlExpression<?>> selections,
      List<HiddenSelection> hiddenSelections,
      TableExpression<?> from,
      @Nullable SqlPredicate where,
      List<? extends SqlExpression<?>> groupBy,
      @Nullable SqlPredicate having,
      List<OrderByItem> orderBy,
      @Nullable SelectPagination pagination) {
    this(
        distinct,
        selections,
        hiddenSelections,
        FromClause.of(from),
        where,
        groupBy,
        having,
        orderBy,
        pagination);
  }

  /**
   * Creates a complete single-table SELECT tree without grouping, preserving the pre-0.2.5 AST
   * construction entry point.
   */
  public SelectStatement(
      boolean distinct,
      List<? extends SqlExpression<?>> selections,
      List<HiddenSelection> hiddenSelections,
      TableExpression<?> from,
      @Nullable SqlPredicate where,
      List<OrderByItem> orderBy,
      @Nullable SelectPagination pagination) {
    this(
        distinct,
        selections,
        hiddenSelections,
        from,
        where,
        List.of(),
        null,
        orderBy,
        pagination);
  }

  /** Creates a single-table SELECT statement. */
  public SelectStatement(
      List<? extends SqlExpression<?>> selections,
      TableExpression<?> from,
      @Nullable SqlPredicate where) {
    this(false, selections, List.of(), from, where, List.of(), null);
  }

  /** Creates a SELECT statement over an explicit FROM/JOIN clause. */
  public SelectStatement(
      List<? extends SqlExpression<?>> selections,
      FromClause fromClause,
      @Nullable SqlPredicate where) {
    this(false, selections, List.of(), fromClause, where, List.of(), null);
  }

  /** Creates a SELECT without a WHERE clause. */
  public SelectStatement(List<? extends SqlExpression<?>> selections, TableExpression<?> from) {
    this(selections, from, null);
  }

  /** Creates a SELECT over an explicit FROM/JOIN clause without a WHERE predicate. */
  public SelectStatement(List<? extends SqlExpression<?>> selections, FromClause fromClause) {
    this(selections, fromClause, null);
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

  public FromClause fromClause() {
    return fromClause;
  }

  /** Returns the root table for source compatibility with the single-table AST. */
  public TableExpression<?> from() {
    return fromClause
        .root()
        .entityTable()
        .orElseThrow(() -> new IllegalStateException("SELECT root is not an entity table"));
  }

  /** Returns the ordered joins in this query block. */
  public List<JoinClause> joins() {
    return fromClause.joins();
  }

  public Optional<SqlPredicate> where() {
    return Optional.ofNullable(where);
  }

  /** Returns the ordered grouping expressions. */
  public List<SqlExpression<?>> groupBy() {
    return groupBy;
  }

  /** Returns the optional HAVING predicate. */
  public Optional<SqlPredicate> having() {
    return Optional.ofNullable(having);
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
            && fromClause.equals(statement.fromClause)
            && Objects.equals(where, statement.where)
            && groupBy.equals(statement.groupBy)
            && Objects.equals(having, statement.having)
            && orderBy.equals(statement.orderBy)
            && Objects.equals(pagination, statement.pagination);
  }

  @Override
  public int hashCode() {
    int result = Boolean.hashCode(distinct);
    result = 31 * result + selections.hashCode();
    result = 31 * result + hiddenSelections.hashCode();
    result = 31 * result + fromClause.hashCode();
    result = 31 * result + Objects.hashCode(where);
    result = 31 * result + groupBy.hashCode();
    result = 31 * result + Objects.hashCode(having);
    result = 31 * result + orderBy.hashCode();
    return 31 * result + Objects.hashCode(pagination);
  }
}
