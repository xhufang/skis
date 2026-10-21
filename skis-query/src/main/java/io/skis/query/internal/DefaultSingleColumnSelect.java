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
import io.skis.sql.ast.SqlExpression;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Reusable SELECT description whose final visible SQL shape contains exactly one value. */
final class DefaultSingleColumnSelect<R extends @Nullable Object>
    extends DefaultSelectDescription<R> implements SingleColumnSelect<R> {

  DefaultSingleColumnSelect(SelectQueryState<R> state) {
    super(state);
    if (state.selected().expressions().size() != 1) {
      throw new IllegalArgumentException("SingleColumnSelect requires exactly one selected value");
    }
  }

  SqlExpression<?> valueExpression() {
    return state().selected().expressions().getFirst();
  }

  Selectable<?> valueSelectable() {
    return state().selected().singleSelectable();
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
  public JoinOnStep<SingleColumnSelect<R>> join(QueryTable<?> table) {
    return innerJoin(table);
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> join(DerivedRelation relation) {
    return innerJoin(relation);
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> innerJoin(QueryTable<?> table) {
    return joinOn(JoinType.INNER, QueryRelations.of(table));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> innerJoin(DerivedRelation relation) {
    return joinOn(JoinType.INNER, QueryRelations.of(relation));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> leftJoin(QueryTable<?> table) {
    return joinOn(JoinType.LEFT, QueryRelations.of(table));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> leftJoin(DerivedRelation relation) {
    return joinOn(JoinType.LEFT, QueryRelations.of(relation));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> rightJoin(QueryTable<?> table) {
    return joinOn(JoinType.RIGHT, QueryRelations.of(table));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> rightJoin(DerivedRelation relation) {
    return joinOn(JoinType.RIGHT, QueryRelations.of(relation));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> fullJoin(QueryTable<?> table) {
    return joinOn(JoinType.FULL, QueryRelations.of(table));
  }

  @Override
  public JoinOnStep<SingleColumnSelect<R>> fullJoin(DerivedRelation relation) {
    return joinOn(JoinType.FULL, QueryRelations.of(relation));
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
  SingleColumnSelect<R> recreate(SelectQueryState<R> replacement) {
    return replacement == state() ? this : new DefaultSingleColumnSelect<>(replacement);
  }

  private JoinOnStep<SingleColumnSelect<R>> joinOn(JoinType type, QueryRelation relation) {
    return new SingleColumnJoinOnStep<>(
        state(), type, Objects.requireNonNull(relation, "relation"));
  }
}

/** Internal ON stage preserving the one-column description type. */
final class SingleColumnJoinOnStep<R extends @Nullable Object>
    implements JoinOnStep<SingleColumnSelect<R>> {

  private final SelectQueryState<R> state;
  private final JoinType type;
  private final QueryRelation relation;

  SingleColumnJoinOnStep(SelectQueryState<R> state, JoinType type, QueryRelation relation) {
    this.state = Objects.requireNonNull(state, "state");
    this.type = Objects.requireNonNull(type, "type");
    if (type == JoinType.CROSS) {
      throw new IllegalArgumentException("CROSS JOIN does not use an ON stage");
    }
    this.relation = Objects.requireNonNull(relation, "relation");
  }

  @Override
  public SingleColumnSelect<R> on(QueryCondition condition) {
    QueryCondition reusable = QueryConditions.reusableStructure(condition);
    return new DefaultSingleColumnSelect<>(state.appendJoin(type, relation, reusable));
  }
}
