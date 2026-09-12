package io.skis.query;

import io.skis.sql.ast.JoinType;
import java.util.Objects;

/**
 * Reusable SELECT description whose final visible SQL shape contains exactly one value column.
 *
 * <p>This contract says nothing about result row count. The selected value is conservatively
 * nullable unless the factory returns {@link NonNullSingleColumnSelect}.
 */
public class SingleColumnSelect<V> extends SelectDescription<V> {

  SingleColumnSelect(SelectQueryState<V> state) {
    super(state);
    if (state.selected().expressions().size() != 1) {
      throw new IllegalArgumentException("SingleColumnSelect requires exactly one selected value");
    }
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
  public <J> SingleColumnSelectJoinOnStep<V, J> join(QueryTable<J> table) {
    return innerJoin(table);
  }

  @Override
  public <J> SingleColumnSelectJoinOnStep<V, J> innerJoin(QueryTable<J> table) {
    return joinOn(JoinType.INNER, table);
  }

  @Override
  public <J> SingleColumnSelectJoinOnStep<V, J> leftJoin(QueryTable<J> table) {
    return joinOn(JoinType.LEFT, table);
  }

  @Override
  public <J> SingleColumnSelectJoinOnStep<V, J> rightJoin(QueryTable<J> table) {
    return joinOn(JoinType.RIGHT, table);
  }

  @Override
  public <J> SingleColumnSelectJoinOnStep<V, J> fullJoin(QueryTable<J> table) {
    return joinOn(JoinType.FULL, table);
  }

  @Override
  public <J> SingleColumnSelect<V> crossJoin(QueryTable<J> table) {
    return require(super.crossJoin(table));
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

  private <J> SingleColumnSelectJoinOnStep<V, J> joinOn(JoinType type, QueryTable<J> table) {
    return new SingleColumnSelectJoinOnStep<>(
        state(), type, Objects.requireNonNull(table, "table"));
  }

  @SuppressWarnings("unchecked")
  private SingleColumnSelect<V> require(SelectDescription<V> description) {
    return (SingleColumnSelect<V>) description;
  }
}
