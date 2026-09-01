package io.skis.sql.ast;

import java.util.Objects;

/** Immutable portable SQL {@code LIKE} predicate. */
public record LikePredicate(SqlExpression<?> value, SqlExpression<?> pattern)
    implements SqlPredicate {

  public LikePredicate {
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(pattern, "pattern");
    SemanticValidator.validateLike(value, pattern);
  }

  @Override
  public Nullability nullability() {
    return value.nullability().union(pattern.nullability());
  }

  @Override
  public boolean nullable() {
    return nullability().isNullable();
  }
}
