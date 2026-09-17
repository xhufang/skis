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
  public NonNullSelectDescriptionJoinOnStep<R> join(QueryTable<?> table) {
    return innerJoin(table);
  }

  @Override
  public NonNullSelectDescriptionJoinOnStep<R> join(DerivedRelation relation) {
    return innerJoin(relation);
  }

  @Override
  public NonNullSelectDescriptionJoinOnStep<R> innerJoin(QueryTable<?> table) {
    return joinOn(JoinType.INNER, table);
  }

  @Override
  public NonNullSelectDescriptionJoinOnStep<R> innerJoin(DerivedRelation relation) {
    return joinOn(JoinType.INNER, relation);
  }

  @Override
  public NonNullSelectDescriptionJoinOnStep<R> leftJoin(QueryTable<?> table) {
    return joinOn(JoinType.LEFT, table);
  }

  @Override
  public NonNullSelectDescriptionJoinOnStep<R> leftJoin(DerivedRelation relation) {
    return joinOn(JoinType.LEFT, relation);
  }

  @Override
  public NonNullSelectDescriptionJoinOnStep<R> rightJoin(QueryTable<?> table) {
    return joinOn(JoinType.RIGHT, table);
  }

  @Override
  public NonNullSelectDescriptionJoinOnStep<R> rightJoin(DerivedRelation relation) {
    return joinOn(JoinType.RIGHT, relation);
  }

  @Override
  public NonNullSelectDescriptionJoinOnStep<R> fullJoin(QueryTable<?> table) {
    return joinOn(JoinType.FULL, table);
  }

  @Override
  public NonNullSelectDescriptionJoinOnStep<R> fullJoin(DerivedRelation relation) {
    return joinOn(JoinType.FULL, relation);
  }

  @Override
  public NonNullSelectDescription<R> crossJoin(QueryTable<?> table) {
    return require(super.crossJoin(table));
  }

  @Override
  public NonNullSelectDescription<R> crossJoin(DerivedRelation relation) {
    return require(super.crossJoin(relation));
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

  private NonNullSelectDescriptionJoinOnStep<R> joinOn(JoinType type, QueryRelation relation) {
    return new NonNullSelectDescriptionJoinOnStep<>(
        state(), type, Objects.requireNonNull(relation, "relation"));
  }

  private NonNullSelectDescription<R> require(SelectDescription<R> description) {
    return (NonNullSelectDescription<R>) description;
  }
}
