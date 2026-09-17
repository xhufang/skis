package io.skis.query;

import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.SqlExpression;
import java.util.Objects;

/**
 * Reusable SELECT description whose final visible SQL shape contains exactly one value column.
 *
 * <p>This contract says nothing about result row count. The selected value is conservatively
 * nullable unless the factory returns {@link NonNullSingleColumnSelect}. Every fluent operation
 * preserves this subtype; an embedding adapter also rechecks the compiled physical output so a
 * hidden selection cannot turn the child into a multi-column SQL result.
 */
public class SingleColumnSelect<V> extends SelectDescription<V> {

  SingleColumnSelect(SelectQueryState<V> state) {
    super(state);
    if (state.selected().expressions().size() != 1) {
      throw new IllegalArgumentException("SingleColumnSelect requires exactly one selected value");
    }
  }

  @SuppressWarnings("unchecked")
  final SqlExpression<V> valueExpression() {
    SqlExpression<?> expression = state().selected().expressions().getFirst();
    return (SqlExpression<V>) expression;
  }

  final Selectable<V> valueSelectable() {
    return state().selected().singleSelectable();
  }

  @Override
  public SingleColumnSelect<V> where(QueryCondition condition) {
    return require(super.where(condition));
  }

  @Override
  public SingleColumnSelect<V> and(QueryCondition condition) {
    return require(super.and(condition));
  }

  @Override
  public SingleColumnSelect<V> or(QueryCondition condition) {
    return require(super.or(condition));
  }

  @Override
  public SingleColumnSelectJoinOnStep<V> join(QueryTable<?> table) {
    return innerJoin(table);
  }

  @Override
  public SingleColumnSelectJoinOnStep<V> join(DerivedRelation relation) {
    return innerJoin(relation);
  }

  @Override
  public SingleColumnSelectJoinOnStep<V> innerJoin(QueryTable<?> table) {
    return joinOn(JoinType.INNER, table);
  }

  @Override
  public SingleColumnSelectJoinOnStep<V> innerJoin(DerivedRelation relation) {
    return joinOn(JoinType.INNER, relation);
  }

  @Override
  public SingleColumnSelectJoinOnStep<V> leftJoin(QueryTable<?> table) {
    return joinOn(JoinType.LEFT, table);
  }

  @Override
  public SingleColumnSelectJoinOnStep<V> leftJoin(DerivedRelation relation) {
    return joinOn(JoinType.LEFT, relation);
  }

  @Override
  public SingleColumnSelectJoinOnStep<V> rightJoin(QueryTable<?> table) {
    return joinOn(JoinType.RIGHT, table);
  }

  @Override
  public SingleColumnSelectJoinOnStep<V> rightJoin(DerivedRelation relation) {
    return joinOn(JoinType.RIGHT, relation);
  }

  @Override
  public SingleColumnSelectJoinOnStep<V> fullJoin(QueryTable<?> table) {
    return joinOn(JoinType.FULL, table);
  }

  @Override
  public SingleColumnSelectJoinOnStep<V> fullJoin(DerivedRelation relation) {
    return joinOn(JoinType.FULL, relation);
  }

  @Override
  public SingleColumnSelect<V> crossJoin(QueryTable<?> table) {
    return require(super.crossJoin(table));
  }

  @Override
  public SingleColumnSelect<V> crossJoin(DerivedRelation relation) {
    return require(super.crossJoin(relation));
  }

  @Override
  public SingleColumnSelect<V> orderBy(SortSpecification... specifications) {
    return require(super.orderBy(specifications));
  }

  @Override
  public SingleColumnSelect<V> thenByPrimaryKey(SortDirection direction) {
    return require(super.thenByPrimaryKey(direction));
  }

  @Override
  public SingleColumnSelect<V> distinct() {
    return require(super.distinct());
  }

  @Override
  SingleColumnSelect<V> recreate(SelectQueryState<V> replacement) {
    return replacement == state() ? this : new SingleColumnSelect<>(replacement);
  }

  private SingleColumnSelectJoinOnStep<V> joinOn(JoinType type, QueryRelation relation) {
    return new SingleColumnSelectJoinOnStep<>(
        state(), type, Objects.requireNonNull(relation, "relation"));
  }

  private SingleColumnSelect<V> require(SelectDescription<V> description) {
    return (SingleColumnSelect<V>) description;
  }
}
