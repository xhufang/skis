package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/** One ordered native MERGE branch; exactly one action payload is present. */
public record MergeAction(
    Kind kind,
    SqlPredicate condition,
    List<? extends SetAssignment> assignments,
    List<ColumnExpression<?, ?>> columns,
    List<SqlExpression<?>> values) {
  public enum Kind {
    UPDATE,
    DELETE,
    INSERT
  }

  public MergeAction {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(condition, "condition");
    assignments = List.copyOf(assignments);
    columns = List.copyOf(columns);
    values = List.copyOf(values);
    if (kind == Kind.UPDATE
        ? assignments.isEmpty() || !columns.isEmpty() || !values.isEmpty()
        : kind == Kind.DELETE
            ? !assignments.isEmpty() || !columns.isEmpty() || !values.isEmpty()
            : !assignments.isEmpty() || columns.isEmpty() || columns.size() != values.size())
      throw new IllegalArgumentException("incomplete or mixed MERGE action payload");
  }

  public static MergeAction update(
      SqlPredicate condition, List<? extends SetAssignment> assignments) {
    return new MergeAction(Kind.UPDATE, condition, assignments, List.of(), List.of());
  }

  public static MergeAction delete(SqlPredicate condition) {
    return new MergeAction(Kind.DELETE, condition, List.of(), List.of(), List.of());
  }

  public static MergeAction insert(
      SqlPredicate condition, List<ColumnExpression<?, ?>> columns, List<SqlExpression<?>> values) {
    return new MergeAction(Kind.INSERT, condition, List.of(), columns, values);
  }
}
