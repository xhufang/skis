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
  public <J> NonNullSingleColumnSelectJoinOnStep<V, J> join(QueryTable<J> table) {
    return innerJoin(table);
  }

  @Override
  public <J> NonNullSingleColumnSelectJoinOnStep<V, J> innerJoin(QueryTable<J> table) {
    return joinOn(JoinType.INNER, table);
  }

  @Override
  public <J> NonNullSingleColumnSelectJoinOnStep<V, J> leftJoin(QueryTable<J> table) {
    return joinOn(JoinType.LEFT, table);
  }

  @Override
  public <J> NonNullSingleColumnSelectJoinOnStep<V, J> rightJoin(QueryTable<J> table) {
    return joinOn(JoinType.RIGHT, table);
  }

  @Override
  public <J> NonNullSingleColumnSelectJoinOnStep<V, J> fullJoin(QueryTable<J> table) {
    return joinOn(JoinType.FULL, table);
  }

  @Override
  public <J> NonNullSingleColumnSelect<V> crossJoin(QueryTable<J> table) {
    return require(super.crossJoin(table));
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

  private <J> NonNullSingleColumnSelectJoinOnStep<V, J> joinOn(JoinType type, QueryTable<J> table) {
    return new NonNullSingleColumnSelectJoinOnStep<>(
        state(), type, Objects.requireNonNull(table, "table"));
  }

  private NonNullSingleColumnSelect<V> require(SingleColumnSelect<V> description) {
    return (NonNullSingleColumnSelect<V>) description;
  }
}
