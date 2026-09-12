package io.skis.query;

import io.skis.sql.ast.JoinType;
import java.util.Objects;

/** Reusable multi-column or entity SELECT description with a non-null result contract. */
public class NonNullSelectDescription<R> extends SelectDescription<R> {

  NonNullSelectDescription(SelectQueryState<R> state) {
    super(state);
  }

  @Override
  public NonNullSelectDescription<R> where(QueryCondition condition) {
    return require(super.where(condition));
  }

  @Override
  public NonNullSelectDescription<R> and(QueryCondition condition) {
    return require(super.and(condition));
  }

  @Override
  public NonNullSelectDescription<R> or(QueryCondition condition) {
    return require(super.or(condition));
  }

  @Override
  public <J> NonNullSelectDescriptionJoinOnStep<R, J> join(QueryTable<J> table) {
    return innerJoin(table);
  }

  @Override
  public <J> NonNullSelectDescriptionJoinOnStep<R, J> innerJoin(QueryTable<J> table) {
    return joinOn(JoinType.INNER, table);
  }

  @Override
  public <J> NonNullSelectDescriptionJoinOnStep<R, J> leftJoin(QueryTable<J> table) {
    return joinOn(JoinType.LEFT, table);
  }

  @Override
  public <J> NonNullSelectDescriptionJoinOnStep<R, J> rightJoin(QueryTable<J> table) {
    return joinOn(JoinType.RIGHT, table);
  }

  @Override
  public <J> NonNullSelectDescriptionJoinOnStep<R, J> fullJoin(QueryTable<J> table) {
    return joinOn(JoinType.FULL, table);
  }

  @Override
  public <J> NonNullSelectDescription<R> crossJoin(QueryTable<J> table) {
    return require(super.crossJoin(table));
  }

  @Override
  public NonNullSelectDescription<R> orderBy(SortSpecification... specifications) {
    return require(super.orderBy(specifications));
  }

  @Override
  public NonNullSelectDescription<R> thenByPrimaryKey(SortDirection direction) {
    return require(super.thenByPrimaryKey(direction));
  }

  @Override
  public NonNullSelectDescription<R> distinct() {
    return require(super.distinct());
  }

  @Override
  NonNullSelectDescription<R> recreate(SelectQueryState<R> replacement) {
    return replacement == state() ? this : new NonNullSelectDescription<>(replacement);
  }

  private <J> NonNullSelectDescriptionJoinOnStep<R, J> joinOn(JoinType type, QueryTable<J> table) {
    return new NonNullSelectDescriptionJoinOnStep<>(
        state(), type, Objects.requireNonNull(table, "table"));
  }

  private NonNullSelectDescription<R> require(SelectDescription<R> description) {
    return (NonNullSelectDescription<R>) description;
  }
}
