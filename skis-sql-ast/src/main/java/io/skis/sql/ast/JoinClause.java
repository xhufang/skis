package io.skis.sql.ast;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Immutable right-hand table join in a left-deep {@link FromClause}. */
public final class JoinClause {

  private final JoinType type;
  private final TableExpression<?> right;
  private final @Nullable SqlPredicate on;

  /**
   * Creates a join while enforcing its local structural invariant: CROSS has no ON predicate and
   * every other join has one.
   */
  public JoinClause(JoinType type, TableExpression<?> right, @Nullable SqlPredicate on) {
    this.type = Objects.requireNonNull(type, "type");
    this.right = Objects.requireNonNull(right, "right");
    if (type == JoinType.CROSS && on != null) {
      throw new IllegalArgumentException("CROSS JOIN must not declare an ON predicate");
    }
    if (type != JoinType.CROSS && on == null) {
      throw new IllegalArgumentException(type + " JOIN requires an ON predicate");
    }
    this.on = on;
  }

  public JoinType type() {
    return type;
  }

  public TableExpression<?> right() {
    return right;
  }

  public Optional<SqlPredicate> on() {
    return Optional.ofNullable(on);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof JoinClause clause
            && type == clause.type
            && right.equals(clause.right)
            && Objects.equals(on, clause.on);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, right, on);
  }
}
