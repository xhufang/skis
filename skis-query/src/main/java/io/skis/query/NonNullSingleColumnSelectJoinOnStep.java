package io.skis.query;

import io.skis.sql.ast.JoinType;

/** Mandatory ON stage preserving one-column and non-null result contracts. */
public final class NonNullSingleColumnSelectJoinOnStep<V>
    extends SingleColumnSelectJoinOnStep<V> {

  NonNullSingleColumnSelectJoinOnStep(
      SelectQueryState<V> state, JoinType type, QueryRelation relation) {
    super(state, type, relation);
  }

  @Override
  public NonNullSingleColumnSelect<V> on(QueryCondition condition) {
    QueryCondition reusable = QueryConditions.reusableStructure(condition);
    return new NonNullSingleColumnSelect<>(state.appendJoin(type, relation, reusable));
  }
}
