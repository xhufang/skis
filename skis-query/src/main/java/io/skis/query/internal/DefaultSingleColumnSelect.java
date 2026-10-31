package io.skis.query.internal;

import io.skis.query.DerivedRelation;
import io.skis.query.JoinOnStep;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnSelect;
import io.skis.query.SortDirection;
import io.skis.query.SortSpecification;
import io.skis.sql.ast.JoinType;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Reusable SELECT description whose final visible SQL shape contains exactly one value. */
final class DefaultSingleColumnSelect<R extends @Nullable Object> extends DefaultSelectQuery<R>
    implements SingleColumnSelect<R> {

  DefaultSingleColumnSelect(
      SelectQueryState<R> state, QueryParameters parameters, Set<QueryParameter<?>> ordering) {
    super(state, parameters, ordering);
    if (state.selected().expressions().size() != 1) {
      throw new IllegalArgumentException("SingleColumnSelect requires exactly one selected value");
    }
  }

  @Override
  public SingleColumnSelect<R> where(QueryCondition condition) {
    return (SingleColumnSelect<R>) super.where(condition);
  }

  @Override
  public SingleColumnSelect<R> and(QueryCondition condition) {
    return (SingleColumnSelect<R>) super.and(condition);
  }

  @Override
  public SingleColumnSelect<R> or(QueryCondition condition) {
    return (SingleColumnSelect<R>) super.or(condition);
  }

  @Override
  public SingleColumnSelect<R> groupBy(Selectable<?>... expressions) {
    return (SingleColumnSelect<R>) super.groupBy(expressions);
  }

  @Override
  public SingleColumnSelect<R> having(QueryCondition condition) {
    return (SingleColumnSelect<R>) super.having(condition);
  }

  @Override
  public SingleColumnSelect<R> andHaving(QueryCondition condition) {
    return (SingleColumnSelect<R>) super.andHaving(condition);
  }

  @Override
  public SingleColumnSelect<R> orHaving(QueryCondition condition) {
    return (SingleColumnSelect<R>) super.orHaving(condition);
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> join(QueryTable<?> table) {
    return innerJoin(table);
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> join(DerivedRelation relation) {
    return innerJoin(relation);
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> innerJoin(QueryTable<?> table) {
    return singleJoinOn(JoinType.INNER, QueryRelations.of(table));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> innerJoin(DerivedRelation relation) {
    return singleJoinOn(JoinType.INNER, QueryRelations.of(relation));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> leftJoin(QueryTable<?> table) {
    return singleJoinOn(JoinType.LEFT, QueryRelations.of(table));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> leftJoin(DerivedRelation relation) {
    return singleJoinOn(JoinType.LEFT, QueryRelations.of(relation));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> rightJoin(QueryTable<?> table) {
    return singleJoinOn(JoinType.RIGHT, QueryRelations.of(table));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> rightJoin(DerivedRelation relation) {
    return singleJoinOn(JoinType.RIGHT, QueryRelations.of(relation));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> fullJoin(QueryTable<?> table) {
    return singleJoinOn(JoinType.FULL, QueryRelations.of(table));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> fullJoin(DerivedRelation relation) {
    return singleJoinOn(JoinType.FULL, QueryRelations.of(relation));
  }

  @Override
  public SingleColumnSelect<R> crossJoin(QueryTable<?> table) {
    return (SingleColumnSelect<R>) super.crossJoin(table);
  }

  @Override
  public SingleColumnSelect<R> crossJoin(DerivedRelation relation) {
    return (SingleColumnSelect<R>) super.crossJoin(relation);
  }

  @Override
  public SingleColumnSelect<R> orderBy(SortSpecification... specifications) {
    return (SingleColumnSelect<R>) super.orderBy(specifications);
  }

  @Override
  public SingleColumnSelect<R> thenByPrimaryKey(SortDirection direction) {
    return (SingleColumnSelect<R>) super.thenByPrimaryKey(direction);
  }

  @Override
  public SingleColumnSelect<R> distinct() {
    return (SingleColumnSelect<R>) super.distinct();
  }

  @Override
  DefaultSingleColumnSelect<R> recreate(
      SelectQueryState<R> replacement, QueryParameters values, Set<QueryParameter<?>> ordering) {
    return new DefaultSingleColumnSelect<>(replacement, values, ordering);
  }

  private JoinOnStep<SingleColumnSelect<R>> singleJoinOn(JoinType type, QueryRelation relation) {
    return condition ->
        (SingleColumnSelect<R>)
            appendJoin(type, relation, Objects.requireNonNull(condition, "condition"));
  }
}
