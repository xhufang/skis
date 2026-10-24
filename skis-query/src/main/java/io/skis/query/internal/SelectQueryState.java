package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.metadata.PrimaryKeyMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.query.DerivedRelation;
import io.skis.query.NullPlacement;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.SortDirection;
import io.skis.query.SortSpecification;
import io.skis.sql.ast.JoinType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Immutable, execution-free SELECT construction state shared by descriptions and queries. */
final class SelectQueryState<R extends @Nullable Object> {

  private final SelectedResult<R> selected;
  private final QueryRelation root;
  private final List<QueryJoin> joins;
  private final @Nullable QueryCondition where;
  private final List<Selectable<?>> groupBy;
  private final @Nullable QueryCondition having;
  private final List<SortSpecification> orderBy;
  private final boolean distinct;
  private final QueryPaginationShape sqlPagination;
  private volatile @Nullable CompiledQueryStructure structure;
  private volatile @Nullable CompiledQueryStructure countStructure;
  private volatile @Nullable SelectedResult<R> resolvedSelected;

  static <R extends @Nullable Object> SelectQueryState<R> create(
      SelectedResult<R> selected, QueryTable<?> root) {
    return create(selected, QueryRelations.of(root));
  }

  static <R extends @Nullable Object> SelectQueryState<R> create(
      SelectedResult<R> selected, DerivedRelation root) {
    return create(selected, QueryRelations.of(root));
  }

  static <R extends @Nullable Object> SelectQueryState<R> create(
      SelectedResult<R> selected, QueryRelation root) {
    return new SelectQueryState<>(
        selected,
        root,
        List.of(),
        null,
        List.of(),
        null,
        List.of(),
        false,
        QueryPaginationShape.none());
  }

  private SelectQueryState(
      SelectedResult<R> selected,
      QueryRelation root,
      List<QueryJoin> joins,
      @Nullable QueryCondition where,
      List<Selectable<?>> groupBy,
      @Nullable QueryCondition having,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPaginationShape sqlPagination) {
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
    SelectedResult<R> existing = resolvedSelected;
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      existing = resolvedSelected;
      if (existing == null) {
        existing = selected.resolveSources(root, joins);
        resolvedSelected = existing;
      }
      return existing;
    }
  }

  QueryRelation root() {
    return root;
  }

  @Nullable QueryTable<?> entityRootOrNull() {
    return root.entityTable();
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

  QueryPaginationShape sqlPagination() {
    return sqlPagination;
  }

  void validateDistinctOrdering() {
    if (!distinct || orderBy.isEmpty()) {
      return;
    }
    List<SelectableSupport.ExpressionIdentity> expressions = selected().expressionIdentities();
    for (SortSpecification item : orderBy) {
      if (!expressions.contains(
          SelectableSupport.expressionIdentity(QueryModels.selectable(item)))) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_ORDER_BY_NOT_SELECTED,
            "distinct ORDER BY expression '"
                + SelectableSupport.summary(QueryModels.selectable(item))
                + "' is not part of the selected result");
      }
    }
  }

  SelectQueryState<R> where(QueryCondition predicate) {
    QueryConditions.framework(predicate);
    if (where != null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_WHERE_CONTRACT, "where(...) may only be called once per query");
    }
    return copy(joins, predicate, groupBy, having, orderBy, distinct);
  }

  SelectQueryState<R> chainWhere(QueryCondition predicate, boolean conjunction) {
    QueryConditions.framework(predicate);
    if (where == null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_WHERE_CONTRACT,
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

  SelectQueryState<R> appendJoin(
      JoinType type, QueryRelation relation, @Nullable QueryCondition on) {
    List<QueryJoin> appended = new ArrayList<>(joins.size() + 1);
    appended.addAll(joins);
    appended.add(new QueryJoin(type, Objects.requireNonNull(relation, "relation"), on));
    return copy(appended, where, groupBy, having, orderBy, distinct);
  }

  SelectQueryState<R> groupBy(List<Selectable<?>> expressions) {
    if (expressions.isEmpty() || !groupBy.isEmpty()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_GROUP_BY_CONTRACT,
          "groupBy requires a nonempty list and may only be called once per query");
    }
    expressions.forEach(SelectableSupport::requireFramework);
    return copy(joins, where, expressions, having, orderBy, distinct);
  }

  SelectQueryState<R> having(QueryCondition predicate) {
    requireHaving(predicate);
    if (having != null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_HAVING_CONTRACT, "having(...) may only be called once per query");
    }
    return copy(joins, where, groupBy, predicate, orderBy, distinct);
  }

  SelectQueryState<R> chainHaving(QueryCondition predicate, boolean conjunction) {
    requireHaving(predicate);
    if (having == null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_HAVING_CONTRACT,
          "andHaving/orHaving requires an existing HAVING predicate");
    }
    return copy(
        joins,
        where,
        groupBy,
        conjunction ? having.and(predicate) : having.or(predicate),
        orderBy,
        distinct);
  }

  static void requireHaving(@Nullable QueryCondition predicate) {
    if (predicate == null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_HAVING_CONTRACT, "HAVING condition must not be null");
    }
    QueryConditions.framework(predicate);
  }

  static List<Selectable<?>> groupItems(@Nullable Selectable<?> @Nullable [] expressions) {
    if (expressions == null || expressions.length == 0) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_GROUP_BY_CONTRACT, "GROUP BY requires a nonempty expression array");
    }
    List<Selectable<?>> items = new ArrayList<>(expressions.length);
    for (Selectable<?> expression : expressions) {
      if (expression == null) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_GROUP_BY_CONTRACT, "GROUP BY expression must not be null");
      }
      items.add(SelectableSupport.requireFramework(expression));
    }
    return List.copyOf(items);
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
    QueryTable<?> entityRoot = entityRootOrNull();
    if (entityRoot == null) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_METADATA_REQUIRED,
          "thenByPrimaryKey requires an entity-table root; this query root is derived");
    }
    return thenByPrimaryKey(entityRoot, direction);
  }

  private <E> SelectQueryState<R> thenByPrimaryKey(
      QueryTable<E> entityRoot, SortDirection direction) {
    PrimaryKeyMeta<E> primaryKey =
        entityRoot
            .entity()
            .primaryKey()
            .orElseThrow(
                () ->
                    QueryValidationException.of(
                        SkisErrorCode.QUERY_METADATA_REQUIRED,
                        "thenByPrimaryKey requires primary-key metadata for entity '"
                            + entityRoot.entity().entityName()
                            + "'"));
    List<SortSpecification> items = new ArrayList<>(orderBy);
    for (PropertyMeta<E, ?> property : primaryKey.properties()) {
      if (!containsRootProperty(items, entityRoot, property)) {
        items.add(
            new DefaultSortSpecification(
                QueryTableModel.of(entityRoot).internalColumn(property),
                direction,
                NullPlacement.DIALECT_DEFAULT));
      }
    }
    return items.equals(orderBy) ? this : orderBy(items);
  }

  SelectQueryState<R> distinctResult() {
    return distinct ? this : copy(joins, where, groupBy, having, orderBy, true);
  }

  SelectQueryState<R> withSqlPagination(QueryPagination pagination) {
    QueryPaginationShape replacement = QueryPaginationShape.from(pagination);
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

  CompiledQueryStructure countStructure() {
    CompiledQueryStructure existing = countStructure;
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      existing = countStructure;
      if (existing == null) {
        existing = QueryStructureCompiler.compileCount(this);
        countStructure = existing;
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

  private static boolean containsRootProperty(
      List<SortSpecification> items, QueryTable<?> entityRoot, PropertyMeta<?, ?> property) {
    return items.stream()
        .anyMatch(
            item ->
                QueryModels.selectable(item) instanceof QueryColumn<?, ?> column
                    && column.table() == entityRoot
                    && column.property() == property);
  }

  private static void validateOrderItems(List<SortSpecification> items) {
    if (items.isEmpty()) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_ORDER_BY_CONTRACT, "orderBy requires at least one ordering item");
    }
    Set<SelectableSupport.ExpressionIdentity> expressions = new HashSet<>();
    for (SortSpecification item : items) {
      Objects.requireNonNull(item, "ordering item");
      if (!expressions.add(SelectableSupport.expressionIdentity(QueryModels.selectable(item)))) {
        throw QueryValidationException.of(
            SkisErrorCode.QUERY_ORDER_BY_CONTRACT,
            "ORDER BY repeats expression '"
                + SelectableSupport.summary(QueryModels.selectable(item))
                + "'");
      }
    }
  }

  private static boolean hasSameOrderOccurrences(
      List<SortSpecification> current, List<SortSpecification> replacement) {
    if (current.size() != replacement.size()) {
      return false;
    }
    for (int index = 0; index < current.size(); index++) {
      if (!QueryModels.sort(current.get(index)).sameOccurrence(replacement.get(index))) {
        return false;
      }
    }
    return true;
  }
}
