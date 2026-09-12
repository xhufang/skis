package io.skis.query;

import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.JoinType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Immutable, execution-free SELECT construction state shared by descriptions and queries. */
final class SelectQueryState<R> {

  private final SelectedResult<R> selected;
  private final QueryTable<?> root;
  private final List<QueryJoin> joins;
  private final @Nullable QueryCondition where;
  private final List<Selectable<?>> groupBy;
  private final @Nullable QueryCondition having;
  private final List<SortSpecification> orderBy;
  private final boolean distinct;
  private final SqlPaginationStructure sqlPagination;
  private volatile @Nullable CompiledQueryStructure structure;

  static <R> SelectQueryState<R> create(SelectedResult<R> selected, QueryTable<?> root) {
    return new SelectQueryState<>(
        selected,
        root,
        List.of(),
        null,
        List.of(),
        null,
        List.of(),
        false,
        SqlPaginationStructure.none());
  }

  private SelectQueryState(
      SelectedResult<R> selected,
      QueryTable<?> root,
      List<QueryJoin> joins,
      @Nullable QueryCondition where,
      List<Selectable<?>> groupBy,
      @Nullable QueryCondition having,
      List<SortSpecification> orderBy,
      boolean distinct,
      SqlPaginationStructure sqlPagination) {
    this.selected = Objects.requireNonNull(selected, "selected");
    this.root = Objects.requireNonNull(root, "root");
    this.joins = List.copyOf(joins);
    this.where = where;
    this.groupBy = List.copyOf(groupBy);
    this.having = having;
    this.orderBy = List.copyOf(orderBy);
    this.distinct = distinct;
    this.sqlPagination = Objects.requireNonNull(sqlPagination, "sqlPagination");
  }

  SelectedResult<R> selected() {
    return selected;
  }

  QueryTable<?> root() {
    return root;
  }

  @SuppressWarnings("unchecked")
  <F> QueryTable<F> typedRoot() {
    return (QueryTable<F>) root;
  }

  List<QueryJoin> joins() {
    return joins;
  }

  @Nullable QueryCondition where() {
    return where;
  }

  List<Selectable<?>> groupBy() {
    return groupBy;
  }

  @Nullable QueryCondition having() {
    return having;
  }

  List<SortSpecification> orderBy() {
    return orderBy;
  }

  boolean distinct() {
    return distinct;
  }

  SqlPaginationStructure sqlPagination() {
    return sqlPagination;
  }

  SelectQueryState<R> where(QueryCondition predicate) {
    Objects.requireNonNull(predicate, "predicate");
    if (where != null) {
      throw new QueryValidationException("where(...) may only be called once per query");
    }
    return copy(joins, predicate, groupBy, having, orderBy, distinct);
  }

  SelectQueryState<R> chainWhere(QueryCondition predicate, boolean conjunction) {
    Objects.requireNonNull(predicate, "predicate");
    if (where == null) {
      throw new QueryValidationException(
          (conjunction ? "and" : "or") + "(...) requires an existing where predicate");
    }
    return copy(
        joins,
        conjunction ? where.and(predicate) : where.or(predicate),
        groupBy,
        having,
        orderBy,
        distinct);
  }

  SelectQueryState<R> appendJoin(JoinType type, QueryTable<?> table, @Nullable QueryCondition on) {
    List<QueryJoin> appended = new ArrayList<>(joins.size() + 1);
    appended.addAll(joins);
    appended.add(new QueryJoin(type, Objects.requireNonNull(table, "table"), on));
    return copy(appended, where, groupBy, having, orderBy, distinct);
  }

  SelectQueryState<R> orderBy(List<SortSpecification> specifications) {
    List<SortSpecification> items = List.copyOf(specifications);
    validateOrderItems(items);
    return hasSameOrderOccurrences(orderBy, items)
        ? this
        : copy(joins, where, groupBy, having, items, distinct);
  }

  SelectQueryState<R> thenByPrimaryKey(SortDirection direction) {
    Objects.requireNonNull(direction, "direction");
    PrimaryKeyMeta<?> primaryKey =
        root.entity()
            .primaryKey()
            .orElseThrow(
                () ->
                    new QueryValidationException(
                        "thenByPrimaryKey requires primary-key metadata for entity '"
                            + root.entity().entityName()
                            + "'"));
    List<SortSpecification> items = new ArrayList<>(orderBy);
    for (PropertyMeta<?, ?> property : primaryKey.properties()) {
      if (!containsRootProperty(items, property)) {
        items.add(
            new SortSpecification(rootColumn(property), direction, NullPlacement.DIALECT_DEFAULT));
      }
    }
    return items.equals(orderBy) ? this : orderBy(items);
  }

  SelectQueryState<R> distinctResult() {
    return distinct ? this : copy(joins, where, groupBy, having, orderBy, true);
  }

  SelectQueryState<R> withSqlPagination(QueryPagination pagination) {
    SqlPaginationStructure replacement = SqlPaginationStructure.from(pagination);
    return replacement.equals(sqlPagination)
        ? this
        : new SelectQueryState<>(
            selected, root, joins, where, groupBy, having, orderBy, distinct, replacement);
  }

  CompiledQueryStructure structure() {
    CompiledQueryStructure existing = structure;
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      existing = structure;
      if (existing == null) {
        existing = QueryStructureCompiler.compile(this);
        structure = existing;
      }
      return existing;
    }
  }

  private SelectQueryState<R> copy(
      List<QueryJoin> newJoins,
      @Nullable QueryCondition newWhere,
      List<Selectable<?>> newGroupBy,
      @Nullable QueryCondition newHaving,
      List<SortSpecification> newOrderBy,
      boolean newDistinct) {
    return new SelectQueryState<>(
        selected,
        root,
        newJoins,
        newWhere,
        newGroupBy,
        newHaving,
        newOrderBy,
        newDistinct,
        sqlPagination);
  }

  private boolean containsRootProperty(List<SortSpecification> items, PropertyMeta<?, ?> property) {
    return items.stream()
        .anyMatch(
            item ->
                item.selectable() instanceof QueryColumn<?, ?> column
                    && column.table() == root
                    && column.property() == property);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private QueryColumn<?, ?> rootColumn(PropertyMeta<?, ?> property) {
    return ((QueryTable) root).queryColumn((PropertyMeta) property);
  }

  private static void validateOrderItems(List<SortSpecification> items) {
    if (items.isEmpty()) {
      throw new QueryValidationException("orderBy requires at least one ordering item");
    }
    Set<io.skis.sql.ast.SqlExpression<?>> expressions = new HashSet<>();
    for (SortSpecification item : items) {
      Objects.requireNonNull(item, "ordering item");
      if (!expressions.add(item.expression())) {
        throw new QueryValidationException(
            "ORDER BY repeats expression '" + SelectableSupport.summary(item.selectable()) + "'");
      }
    }
  }

  private static boolean hasSameOrderOccurrences(
      List<SortSpecification> current, List<SortSpecification> replacement) {
    if (current.size() != replacement.size()) {
      return false;
    }
    for (int index = 0; index < current.size(); index++) {
      if (!current.get(index).sameOccurrence(replacement.get(index))) {
        return false;
      }
    }
    return true;
  }
}

/** Value-free SQL pagination shape combined with a description only for one final statement. */
record SqlPaginationStructure(Mode mode, List<Boolean> keysetNullMarkers) {

  private static final SqlPaginationStructure NONE =
      new SqlPaginationStructure(Mode.NONE, List.of());

  SqlPaginationStructure {
    Objects.requireNonNull(mode, "mode");
    keysetNullMarkers = List.copyOf(keysetNullMarkers);
    if (mode != Mode.KEYSET && !keysetNullMarkers.isEmpty()) {
      throw new IllegalArgumentException("only keyset pagination has null-marker structure");
    }
  }

  static SqlPaginationStructure none() {
    return NONE;
  }

  static SqlPaginationStructure from(QueryPagination pagination) {
    Objects.requireNonNull(pagination, "pagination");
    return switch (pagination) {
      case QueryPagination.None ignored -> NONE;
      case QueryPagination.LimitOnly ignored -> new SqlPaginationStructure(Mode.LIMIT, List.of());
      case QueryPagination.Offset ignored -> new SqlPaginationStructure(Mode.OFFSET, List.of());
      case QueryPagination.Keyset keyset ->
          new SqlPaginationStructure(
              Mode.KEYSET, keyset.values().stream().map(Objects::isNull).toList());
    };
  }

  enum Mode {
    NONE,
    LIMIT,
    OFFSET,
    KEYSET
  }
}
