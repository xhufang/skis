package io.skis.query;

import io.skis.sql.ast.JoinType;

/** Mandatory ON stage preserving a SELECT description's one-column shape. */
public class SingleColumnSelectJoinOnStep<V> extends SelectDescriptionJoinOnStep<V> {

  SingleColumnSelectJoinOnStep(SelectQueryState<V> state, JoinType type, QueryRelation relation) {
    super(state, type, relation);
  }

  @Override
  public SingleColumnSelect<V> on(QueryCondition condition) {
    QueryCondition reusable = QueryConditions.reusableStructure(condition);
    return new SingleColumnSelect<>(state.appendJoin(type, relation, reusable));
  }
}
