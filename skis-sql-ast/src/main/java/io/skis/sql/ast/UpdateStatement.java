package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable single-table UPDATE statement with a required safety predicate. */
public record UpdateStatement(
    TableExpression<?> target,
    List<? extends SetAssignment> assignments,
    SqlPredicate where,
    @Nullable FromClause from)
    implements StatementAst {

  /** Validates the target, assignments, and required WHERE predicate. */
  public UpdateStatement {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(assignments, "assignments");
    Objects.requireNonNull(where, "where");
    assignments = List.copyOf(assignments);
    if (assignments.isEmpty()) {
      throw new IllegalArgumentException("UPDATE requires at least one assignment");
    }
    for (SetAssignment assignment : assignments) {
      Objects.requireNonNull(assignment, "assignment");
    }
    SemanticValidator.validateUpdate(target, assignments, where, from);
  }

  public UpdateStatement(
      TableExpression<?> target, List<? extends SetAssignment> assignments, SqlPredicate where) {
    this(target, assignments, where, null);
  }

  public FromClause scope() {
    return MutationScope.of(target, from);
  }
}
