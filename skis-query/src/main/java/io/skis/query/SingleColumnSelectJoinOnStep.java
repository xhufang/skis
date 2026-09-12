package io.skis.query;

import io.skis.sql.ast.JoinType;

/** Mandatory ON stage preserving a SELECT description's one-column shape. */
public class SingleColumnSelectJoinOnStep<V, J> extends SelectDescriptionJoinOnStep<V, J> {

  SingleColumnSelectJoinOnStep(SelectQueryState<V> state, JoinType type, QueryTable<J> table) {
    super(state, type, table);
  }

  @Override
  public SingleColumnSelect<V> on(QueryCondition condition) {
    QueryCondition reusable = QueryConditions.reusableStructure(condition);
    return new SingleColumnSelect<>(state.appendJoin(type, table, reusable));
  }
}
