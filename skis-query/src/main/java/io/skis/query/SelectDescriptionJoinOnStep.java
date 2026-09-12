package io.skis.query;

import io.skis.sql.ast.JoinType;
import java.util.Objects;

/** Mandatory ON stage for an execution-free SELECT description Join. */
public class SelectDescriptionJoinOnStep<R, J> {

  final SelectQueryState<R> state;
  final JoinType type;
  final QueryTable<J> table;

  SelectDescriptionJoinOnStep(SelectQueryState<R> state, JoinType type, QueryTable<J> table) {
    this.state = Objects.requireNonNull(state, "state");
    this.type = Objects.requireNonNull(type, "type");
    this.table = Objects.requireNonNull(table, "table");
  }

  /** Completes the Join without adding execution capability to the description. */
  public SelectDescription<R> on(QueryCondition condition) {
    QueryCondition reusable = QueryConditions.reusableStructure(condition);
    return new SelectDescription<>(state.appendJoin(type, table, reusable));
  }
}
