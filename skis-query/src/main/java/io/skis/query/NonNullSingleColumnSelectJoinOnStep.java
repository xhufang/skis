package io.skis.query;

import io.skis.sql.ast.JoinType;

/** Mandatory ON stage preserving one-column and non-null result contracts. */
public final class NonNullSingleColumnSelectJoinOnStep<V, J>
    extends SingleColumnSelectJoinOnStep<V, J> {

  NonNullSingleColumnSelectJoinOnStep(
      SelectQueryState<V> state, JoinType type, QueryTable<J> table) {
    super(state, type, table);
  }

  @Override
  public NonNullSingleColumnSelect<V> on(QueryCondition condition) {
    QueryCondition reusable = QueryConditions.reusableStructure(condition);
    return new NonNullSingleColumnSelect<>(state.appendJoin(type, table, reusable));
  }
}
