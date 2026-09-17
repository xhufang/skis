package io.skis.query;

import io.skis.sql.ast.JoinType;
import java.util.Objects;

/** Mandatory ON stage for an execution-free SELECT description Join. */
public class SelectDescriptionJoinOnStep<R> {

  final SelectQueryState<R> state;
  final JoinType type;
  final QueryRelation relation;

  SelectDescriptionJoinOnStep(SelectQueryState<R> state, JoinType type, QueryRelation relation) {
    this.state = Objects.requireNonNull(state, "state");
    this.type = Objects.requireNonNull(type, "type");
    this.relation = Objects.requireNonNull(relation, "relation");
  }

  /** Completes the Join without adding execution capability to the description. */
  public SelectDescription<R> on(QueryCondition condition) {
    QueryCondition reusable = QueryConditions.reusableStructure(condition);
    return new SelectDescription<>(state.appendJoin(type, relation, reusable));
  }
}
