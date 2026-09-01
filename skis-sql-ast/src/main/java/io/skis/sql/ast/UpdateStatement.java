package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/** Immutable single-table UPDATE statement with a required safety predicate. */
public record UpdateStatement(
    TableExpression<?> target, List<UpdateAssignment<?>> assignments, SqlPredicate where)
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
    for (UpdateAssignment<?> assignment : assignments) {
      Objects.requireNonNull(assignment, "assignment");
    }
    SemanticValidator.validateUpdate(target, assignments, where);
  }
}
