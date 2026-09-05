package io.skis.query;

import io.skis.sql.ast.JoinType;
import java.util.Objects;

/** Built-in immutable required-ON stage for a nullable result query. */
final class DefaultNullableJoinOnStep<F, R, J> implements NullableJoinOnStep<F, R, J> {

  private final DefaultNullableSelectQuery<F, R> query;
  private final JoinType type;
  private final QueryTable<J> right;

  DefaultNullableJoinOnStep(
      DefaultNullableSelectQuery<F, R> query, JoinType type, QueryTable<J> right) {
    this.query = Objects.requireNonNull(query, "query");
    this.type = Objects.requireNonNull(type, "type");
    if (type == JoinType.CROSS) {
      throw new IllegalArgumentException("CROSS JOIN does not use an ON stage");
    }
    this.right = Objects.requireNonNull(right, "right");
  }

  @Override
  public NullableSelectQuery<F, R> on(QueryCondition condition) {
    return query.appendJoin(type, right, Objects.requireNonNull(condition, "condition"));
  }
}
