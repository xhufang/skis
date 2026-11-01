package io.skis.query.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.spi.ExecutionContext;
import io.skis.query.DerivedRelation;
import io.skis.query.ExecutableSingleColumnSelect;
import io.skis.query.JoinOnStep;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.SortDirection;
import io.skis.query.SortSpecification;
import io.skis.sql.ast.JoinType;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Reusable SELECT description whose final visible SQL shape contains exactly one value. */
final class DefaultExecutableSingleColumnSelect<R extends @Nullable Object>
    extends DefaultExecutableSelectQuery<R> implements ExecutableSingleColumnSelect<R> {

  DefaultExecutableSingleColumnSelect(
      DefaultQueryOperations operations,
      QueryPlanCompiler compiler,
      @Nullable EntityPlanSet<?> plans,
      SelectQueryState<R> state,
      QueryParameters parameters,
      ExecutionContext context,
      Set<QueryParameter<?>> ordering) {
    super(operations, compiler, plans, state, parameters, context, ordering, null);
  }

  private DefaultExecutableSingleColumnSelect(
      DefaultExecutableSingleColumnSelect<R> source, ExecutionContext context) {
    super(
        source.operations,
        source.compiler,
        source.fastPlans,
        source.state,
        source.parameters,
        context,
        source.orderingOnlyParameters,
        source.caches);
  }

  @Override
  public DefaultExecutableSingleColumnSelect<R> withOptions(ExecutionOptions options) {
    Objects.requireNonNull(options, "options");
    return executionContext.executionOptions().equals(options)
        ? this
        : new DefaultExecutableSingleColumnSelect<>(this, ExecutionContext.of(options));
  }

  @Override
  public ExecutableSingleColumnSelect<R> where(QueryCondition condition) {
    return (ExecutableSingleColumnSelect<R>) super.where(condition);
  }

  @Override
  public ExecutableSingleColumnSelect<R> and(QueryCondition condition) {
    return (ExecutableSingleColumnSelect<R>) super.and(condition);
  }

  @Override
  public ExecutableSingleColumnSelect<R> or(QueryCondition condition) {
    return (ExecutableSingleColumnSelect<R>) super.or(condition);
  }

  @Override
  public ExecutableSingleColumnSelect<R> groupBy(Selectable<?>... expressions) {
    return (ExecutableSingleColumnSelect<R>) super.groupBy(expressions);
  }

  @Override
  public ExecutableSingleColumnSelect<R> having(QueryCondition condition) {
    return (ExecutableSingleColumnSelect<R>) super.having(condition);
  }

  @Override
  public ExecutableSingleColumnSelect<R> andHaving(QueryCondition condition) {
    return (ExecutableSingleColumnSelect<R>) super.andHaving(condition);
  }

  @Override
  public ExecutableSingleColumnSelect<R> orHaving(QueryCondition condition) {
    return (ExecutableSingleColumnSelect<R>) super.orHaving(condition);
  }

  @Override
  public JoinOnStep<ExecutableSingleColumnSelect<R>> join(QueryTable<?> table) {
    return innerJoin(table);
  }

  @Override
  public JoinOnStep<ExecutableSingleColumnSelect<R>> join(DerivedRelation relation) {
    return innerJoin(relation);
  }

  @Override
  public JoinOnStep<ExecutableSingleColumnSelect<R>> innerJoin(QueryTable<?> table) {
    return singleJoinOn(JoinType.INNER, QueryRelations.of(table));
  }

  @Override
  public JoinOnStep<ExecutableSingleColumnSelect<R>> innerJoin(DerivedRelation relation) {
    return singleJoinOn(JoinType.INNER, QueryRelations.of(relation));
  }

  @Override
  public JoinOnStep<ExecutableSingleColumnSelect<R>> leftJoin(QueryTable<?> table) {
    return singleJoinOn(JoinType.LEFT, QueryRelations.of(table));
  }

  @Override
  public JoinOnStep<ExecutableSingleColumnSelect<R>> leftJoin(DerivedRelation relation) {
    return singleJoinOn(JoinType.LEFT, QueryRelations.of(relation));
  }

  @Override
  public JoinOnStep<ExecutableSingleColumnSelect<R>> rightJoin(QueryTable<?> table) {
    return singleJoinOn(JoinType.RIGHT, QueryRelations.of(table));
  }

  @Override
  public JoinOnStep<ExecutableSingleColumnSelect<R>> rightJoin(DerivedRelation relation) {
    return singleJoinOn(JoinType.RIGHT, QueryRelations.of(relation));
  }

  @Override
  public JoinOnStep<ExecutableSingleColumnSelect<R>> fullJoin(QueryTable<?> table) {
    return singleJoinOn(JoinType.FULL, QueryRelations.of(table));
  }

  @Override
  public JoinOnStep<ExecutableSingleColumnSelect<R>> fullJoin(DerivedRelation relation) {
    return singleJoinOn(JoinType.FULL, QueryRelations.of(relation));
  }

  @Override
  public ExecutableSingleColumnSelect<R> crossJoin(QueryTable<?> table) {
    return (ExecutableSingleColumnSelect<R>) super.crossJoin(table);
  }

  @Override
  public ExecutableSingleColumnSelect<R> crossJoin(DerivedRelation relation) {
    return (ExecutableSingleColumnSelect<R>) super.crossJoin(relation);
  }

  @Override
  public ExecutableSingleColumnSelect<R> orderBy(SortSpecification... specifications) {
    return (ExecutableSingleColumnSelect<R>) super.orderBy(specifications);
  }

  @Override
  public ExecutableSingleColumnSelect<R> thenByPrimaryKey(SortDirection direction) {
    return (ExecutableSingleColumnSelect<R>) super.thenByPrimaryKey(direction);
  }

  @Override
  public ExecutableSingleColumnSelect<R> distinct() {
    return (ExecutableSingleColumnSelect<R>) super.distinct();
  }

  @Override
  DefaultExecutableSingleColumnSelect<R> recreate(
      SelectQueryState<R> replacement, QueryParameters values, Set<QueryParameter<?>> ordering) {
    return new DefaultExecutableSingleColumnSelect<>(
        operations, compiler, fastPlans, replacement, values, executionContext, ordering);
  }

  private JoinOnStep<ExecutableSingleColumnSelect<R>> singleJoinOn(
      JoinType type, QueryRelation relation) {
    return condition ->
        (ExecutableSingleColumnSelect<R>)
            appendJoin(type, relation, Objects.requireNonNull(condition, "condition"));
  }
}
