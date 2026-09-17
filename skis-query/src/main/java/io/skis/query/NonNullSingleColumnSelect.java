package io.skis.query;

import io.skis.sql.ast.JoinType;
import java.util.Objects;

/** One-column reusable SELECT description with a declared non-null value contract. */
public final class NonNullSingleColumnSelect<V> extends SingleColumnSelect<V> {

  NonNullSingleColumnSelect(SelectQueryState<V> state) {
    super(state);
  }

  @Override
  public NonNullSingleColumnSelect<V> where(QueryCondition condition) {
    return require(super.where(condition));
  }

  @Override
  public NonNullSingleColumnSelect<V> and(QueryCondition condition) {
    return require(super.and(condition));
  }

  @Override
  public NonNullSingleColumnSelect<V> or(QueryCondition condition) {
    return require(super.or(condition));
  }

  @Override
  public NonNullSingleColumnSelectJoinOnStep<V> join(QueryTable<?> table) {
    return innerJoin(table);
  }

  @Override
  public NonNullSingleColumnSelectJoinOnStep<V> join(DerivedRelation relation) {
    return innerJoin(relation);
  }

  @Override
  public NonNullSingleColumnSelectJoinOnStep<V> innerJoin(QueryTable<?> table) {
    return joinOn(JoinType.INNER, table);
  }

  @Override
  public NonNullSingleColumnSelectJoinOnStep<V> innerJoin(DerivedRelation relation) {
    return joinOn(JoinType.INNER, relation);
  }

  @Override
  public NonNullSingleColumnSelectJoinOnStep<V> leftJoin(QueryTable<?> table) {
    return joinOn(JoinType.LEFT, table);
  }

  @Override
  public NonNullSingleColumnSelectJoinOnStep<V> leftJoin(DerivedRelation relation) {
    return joinOn(JoinType.LEFT, relation);
  }

  @Override
  public NonNullSingleColumnSelectJoinOnStep<V> rightJoin(QueryTable<?> table) {
    return joinOn(JoinType.RIGHT, table);
  }

  @Override
  public NonNullSingleColumnSelectJoinOnStep<V> rightJoin(DerivedRelation relation) {
    return joinOn(JoinType.RIGHT, relation);
  }

  @Override
  public NonNullSingleColumnSelectJoinOnStep<V> fullJoin(QueryTable<?> table) {
    return joinOn(JoinType.FULL, table);
  }

  @Override
  public NonNullSingleColumnSelectJoinOnStep<V> fullJoin(DerivedRelation relation) {
    return joinOn(JoinType.FULL, relation);
  }

  @Override
  public NonNullSingleColumnSelect<V> crossJoin(QueryTable<?> table) {
    return require(super.crossJoin(table));
  }

  @Override
  public NonNullSingleColumnSelect<V> crossJoin(DerivedRelation relation) {
    return require(super.crossJoin(relation));
  }

  @Override
  public NonNullSingleColumnSelect<V> orderBy(SortSpecification... specifications) {
    return require(super.orderBy(specifications));
  }

  @Override
  public NonNullSingleColumnSelect<V> thenByPrimaryKey(SortDirection direction) {
    return require(super.thenByPrimaryKey(direction));
  }

  @Override
  public NonNullSingleColumnSelect<V> distinct() {
    return require(super.distinct());
  }

  @Override
  NonNullSingleColumnSelect<V> recreate(SelectQueryState<V> replacement) {
    return replacement == state() ? this : new NonNullSingleColumnSelect<>(replacement);
  }

  private NonNullSingleColumnSelectJoinOnStep<V> joinOn(JoinType type, QueryRelation relation) {
    return new NonNullSingleColumnSelectJoinOnStep<>(
        state(), type, Objects.requireNonNull(relation, "relation"));
  }

  private NonNullSingleColumnSelect<V> require(SingleColumnSelect<V> description) {
    return (NonNullSingleColumnSelect<V>) description;
  }
}
