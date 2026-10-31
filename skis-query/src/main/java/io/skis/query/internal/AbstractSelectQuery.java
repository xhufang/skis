package io.skis.query.internal;

import io.skis.query.DerivedRelation;
import io.skis.query.JoinOnStep;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;
import io.skis.query.SelectSource;
import io.skis.query.Selectable;
import io.skis.query.SortDirection;
import io.skis.query.SortSpecification;
import io.skis.sql.ast.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** One construction implementation for independent models and executable views. */
abstract class AbstractSelectQuery<R extends @Nullable Object, Q extends SelectSource<R>>
    implements SelectSource<R> {

  final SelectQueryState<R> state;
  final QueryParameters parameters;
  final Set<QueryParameter<?>> orderingOnlyParameters;
  // Execution-option-independent analyses and plans of this immutable query for one compiler.
  private volatile DefaultExecutableSelectQuery.@Nullable CompiledState<R> compiled;

  AbstractSelectQuery(
      SelectQueryState<R> state,
      QueryParameters parameters,
      Set<QueryParameter<?>> orderingOnlyParameters) {
    this.state = Objects.requireNonNull(state, "state");
    this.parameters = Objects.requireNonNull(parameters, "parameters");
    this.orderingOnlyParameters =
        Objects.requireNonNull(orderingOnlyParameters, "orderingOnlyParameters");
  }

  abstract Q recreate(
      SelectQueryState<R> state, QueryParameters parameters, Set<QueryParameter<?>> orderingOnly);

  @SuppressWarnings("unchecked")
  private Q self() {
    return (Q) this;
  }

  final SelectQueryState<R> state() {
    return state;
  }

  /**
   * Returns the compiled caches shared by every execution of this query through {@code compiler}. A
   * different plan catalog (compiler) replaces them, so plans never cross catalogs.
   */
  final DefaultExecutableSelectQuery.CompiledState<R> compiled(QueryPlanCompiler compiler) {
    DefaultExecutableSelectQuery.CompiledState<R> existing = compiled;
    if (existing != null && existing.belongsTo(compiler)) {
      return existing;
    }
    DefaultExecutableSelectQuery.CompiledState<R> created =
        new DefaultExecutableSelectQuery.CompiledState<>(compiler);
    compiled = created;
    return created;
  }

  public Q where(QueryCondition condition) {
    ExpressionCapture capture = new ExpressionCapture();
    return captured(state.where(capture.condition(condition)), capture.parameters());
  }

  public Q and(QueryCondition condition) {
    return chain(condition, true);
  }

  public Q or(QueryCondition condition) {
    return chain(condition, false);
  }

  private Q chain(QueryCondition condition, boolean conjunction) {
    ExpressionCapture capture = new ExpressionCapture();
    return captured(
        state.chainWhere(capture.condition(condition), conjunction), capture.parameters());
  }

  public Q groupBy(Selectable<?>... expressions) {
    List<Selectable<?>> items = SelectQueryState.groupItems(expressions);
    ExpressionCapture capture = new ExpressionCapture();
    List<Selectable<?>> structures = new ArrayList<>(items.size());
    for (Selectable<?> item : items) structures.add(capture.selectable(item));
    return captured(state.groupBy(structures), capture.parameters());
  }

  public Q having(QueryCondition condition) {
    return having(condition, null);
  }

  public Q andHaving(QueryCondition condition) {
    return having(condition, true);
  }

  public Q orHaving(QueryCondition condition) {
    return having(condition, false);
  }

  private Q having(QueryCondition condition, @Nullable Boolean conjunction) {
    SelectQueryState.requireHaving(condition);
    ExpressionCapture capture = new ExpressionCapture();
    QueryCondition structure = capture.condition(condition);
    return captured(
        conjunction == null ? state.having(structure) : state.chainHaving(structure, conjunction),
        capture.parameters());
  }

  public JoinOnStep<? extends Q> join(QueryTable<?> table) {
    return innerJoin(table);
  }

  public JoinOnStep<? extends Q> join(DerivedRelation relation) {
    return innerJoin(relation);
  }

  public JoinOnStep<? extends Q> innerJoin(QueryTable<?> table) {
    return joinOn(JoinType.INNER, QueryRelations.of(table));
  }

  public JoinOnStep<? extends Q> innerJoin(DerivedRelation relation) {
    return joinOn(JoinType.INNER, QueryRelations.of(relation));
  }

  public JoinOnStep<? extends Q> leftJoin(QueryTable<?> table) {
    return joinOn(JoinType.LEFT, QueryRelations.of(table));
  }

  public JoinOnStep<? extends Q> leftJoin(DerivedRelation relation) {
    return joinOn(JoinType.LEFT, QueryRelations.of(relation));
  }

  public JoinOnStep<? extends Q> rightJoin(QueryTable<?> table) {
    return joinOn(JoinType.RIGHT, QueryRelations.of(table));
  }

  public JoinOnStep<? extends Q> rightJoin(DerivedRelation relation) {
    return joinOn(JoinType.RIGHT, QueryRelations.of(relation));
  }

  public JoinOnStep<? extends Q> fullJoin(QueryTable<?> table) {
    return joinOn(JoinType.FULL, QueryRelations.of(table));
  }

  public JoinOnStep<? extends Q> fullJoin(DerivedRelation relation) {
    return joinOn(JoinType.FULL, QueryRelations.of(relation));
  }

  final JoinOnStep<Q> joinOn(JoinType type, QueryRelation relation) {
    Objects.requireNonNull(relation, "relation");
    return condition -> appendJoin(type, relation, Objects.requireNonNull(condition, "condition"));
  }

  public Q crossJoin(QueryTable<?> table) {
    return appendJoin(JoinType.CROSS, QueryRelations.of(table), null);
  }

  public Q crossJoin(DerivedRelation relation) {
    return appendJoin(JoinType.CROSS, QueryRelations.of(relation), null);
  }

  final Q appendJoin(JoinType type, QueryRelation relation, @Nullable QueryCondition on) {
    ExpressionCapture capture = new ExpressionCapture();
    capture.include(QueryRelations.parameters(relation));
    QueryCondition structure = on == null ? null : capture.condition(on);
    return captured(
        state.appendJoin(type, QueryRelations.structure(relation), structure),
        capture.parameters());
  }

  public Q orderBy(SortSpecification... specifications) {
    Objects.requireNonNull(specifications, "specifications");
    ExpressionCapture capture = new ExpressionCapture();
    List<SortSpecification> items = new ArrayList<>(specifications.length);
    for (SortSpecification specification : specifications) {
      DefaultSortSpecification sort = QueryModels.sort(specification);
      Selectable<?> source = sort.selectable();
      items.add(
          ExpressionCapture.capturesValues(source)
              ? new DefaultSortSpecification(
                  capture.selectable(source), sort.direction(), sort.nullPlacement())
              : specification);
    }
    QueryParameters ordering = capture.parameters();
    return recreate(
        state.orderBy(items),
        parameters.replace(orderingOnlyParameters, ordering),
        parameters.replacementOnlyReferences(orderingOnlyParameters, ordering));
  }

  public Q thenByPrimaryKey(SortDirection direction) {
    SelectQueryState<R> replacement = state.thenByPrimaryKey(direction);
    return replacement == state
        ? self()
        : recreate(replacement, parameters, orderingOnlyParameters);
  }

  public Q distinct() {
    SelectQueryState<R> replacement = state.distinctResult();
    return replacement == state
        ? self()
        : recreate(replacement, parameters, orderingOnlyParameters);
  }

  private Q captured(SelectQueryState<R> replacement, QueryParameters captured) {
    return recreate(
        replacement,
        parameters.merge(captured),
        captured.withoutBoundReferences(orderingOnlyParameters));
  }

  @Override
  public final DerivedRelation as(String alias) {
    return QueryDsl.source(this, alias);
  }
}
