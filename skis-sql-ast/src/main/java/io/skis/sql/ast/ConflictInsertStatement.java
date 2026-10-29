package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/** PostgreSQL's explicit-column ON CONFLICT grammar; no cross-dialect emulation. */
public record ConflictInsertStatement(
    InsertStatement insert,
    List<ColumnExpression<?, ?>> conflictColumns,
    List<UpdateAssignment<?>> assignments,
    SqlPredicate updateWhere,
    boolean doNothing)
    implements StatementAst {
  public ConflictInsertStatement {
    Objects.requireNonNull(insert, "insert");
    conflictColumns = List.copyOf(conflictColumns);
    assignments = List.copyOf(assignments);
    Objects.requireNonNull(updateWhere, "updateWhere");
    if (conflictColumns.isEmpty() || doNothing != assignments.isEmpty()) {
      throw new IllegalArgumentException("conflict target and complete action are required");
    }
  }

  public TableExpression<?> target() {
    return insert.target();
  }
}
