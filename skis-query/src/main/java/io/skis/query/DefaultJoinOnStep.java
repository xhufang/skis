package io.skis.query;

import io.skis.sql.ast.JoinType;
import java.util.Objects;

/** Built-in immutable required-ON stage. */
final class DefaultJoinOnStep<F, R, J> implements JoinOnStep<F, R, J> {

  private final DefaultSelectQuery<F, R> query;
  private final JoinType type;
  private final QueryTable<J> right;

  DefaultJoinOnStep(DefaultSelectQuery<F, R> query, JoinType type, QueryTable<J> right) {
    this.query = Objects.requireNonNull(query, "query");
    this.type = Objects.requireNonNull(type, "type");
    if (type == JoinType.CROSS) {
      throw new IllegalArgumentException("CROSS JOIN does not use an ON stage");
    }
    this.right = Objects.requireNonNull(right, "right");
  }

  @Override
  public SelectQuery<F, R> on(QueryCondition condition) {
    return query.appendJoin(type, right, Objects.requireNonNull(condition, "condition"));
  }
}
