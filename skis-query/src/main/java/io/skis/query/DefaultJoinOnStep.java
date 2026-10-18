package io.skis.query;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.sql.ast.JoinType;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Built-in immutable required-ON stage. */
final class DefaultJoinOnStep<R extends @Nullable Object> implements JoinOnStep<SelectQuery<R>> {

  private final DefaultSelectQuery<R> query;
  private final JoinType type;
  private final QueryRelation right;

  DefaultJoinOnStep(DefaultSelectQuery<R> query, JoinType type, QueryRelation right) {
    this.query = Objects.requireNonNull(query, "query");
    this.type = Objects.requireNonNull(type, "type");
    if (type == JoinType.CROSS) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_JOIN_CONTRACT, "CROSS JOIN does not use an ON stage");
    }
    this.right = Objects.requireNonNull(right, "right");
  }

  @Override
  public SelectQuery<R> on(QueryCondition condition) {
    return query.appendJoin(type, right, Objects.requireNonNull(condition, "condition"));
  }
}
