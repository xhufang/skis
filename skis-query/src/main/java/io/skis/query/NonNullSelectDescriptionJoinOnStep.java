package io.skis.query;

import io.skis.sql.ast.JoinType;

/** Mandatory ON stage preserving a description's non-null result contract. */
public class NonNullSelectDescriptionJoinOnStep<R> extends SelectDescriptionJoinOnStep<R> {

  NonNullSelectDescriptionJoinOnStep(
      SelectQueryState<R> state, JoinType type, QueryRelation relation) {
    super(state, type, relation);
  }

  @Override
  public NonNullSelectDescription<R> on(QueryCondition condition) {
    QueryCondition reusable = QueryConditions.reusableStructure(condition);
    return new NonNullSelectDescription<>(state.appendJoin(type, relation, reusable));
  }
}
