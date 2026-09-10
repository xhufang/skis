package io.skis.sql.ast;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Immutable right-hand relation join in a left-deep {@link FromClause}. */
public final class JoinClause {

  private final JoinType type;
  private final RelationSource right;
  private final @Nullable SqlPredicate on;

  /**
   * Creates a join while enforcing its local structural invariant: CROSS has no ON predicate and
   * every other join has one.
   */
  public JoinClause(JoinType type, RelationSource right, @Nullable SqlPredicate on) {
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

  /** Creates a join by adapting the entity table without losing its object identity. */
  public JoinClause(JoinType type, TableExpression<?> right, @Nullable SqlPredicate on) {
    this(type, RelationSource.entity(right), on);
  }

  public JoinType type() {
    return type;
  }

  public RelationSource right() {
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
