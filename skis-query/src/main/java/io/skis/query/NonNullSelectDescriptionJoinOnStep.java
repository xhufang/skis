package io.skis.query;

import io.skis.sql.ast.JoinType;

/** Mandatory ON stage preserving a description's non-null result contract. */
public class NonNullSelectDescriptionJoinOnStep<R, J> extends SelectDescriptionJoinOnStep<R, J> {

  NonNullSelectDescriptionJoinOnStep(
      SelectQueryState<R> state, JoinType type, QueryTable<J> table) {
    super(state, type, table);
  }

  @Override
  public NonNullSelectDescription<R> on(QueryCondition condition) {
    QueryCondition reusable = QueryConditions.reusableStructure(condition);
    return new NonNullSelectDescription<>(state.appendJoin(type, table, reusable));
  }
}
