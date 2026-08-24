package io.skis.sql.ast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Immutable single-table SELECT statement used by the initial renderer slice. */
public final class SelectStatement implements StatementAst {

  private final List<SqlExpression<?>> selections;
  private final TableExpression<?> from;
  private final @Nullable SqlPredicate where;

  /** Creates a single-table SELECT statement. */
  public SelectStatement(
      List<? extends SqlExpression<?>> selections,
      TableExpression<?> from,
      @Nullable SqlPredicate where) {
    Objects.requireNonNull(selections, "selections");
    this.selections = List.copyOf(selections);
    if (this.selections.isEmpty()) {
      throw new IllegalArgumentException("SELECT requires at least one expression");
    }
    this.from = Objects.requireNonNull(from, "from");
    this.where = where;
    validateParameterSlots(this.selections, where);
  }

  /** Creates a SELECT without a WHERE clause. */
  public SelectStatement(List<? extends SqlExpression<?>> selections, TableExpression<?> from) {
    this(selections, from, null);
  }

  public List<SqlExpression<?>> selections() {
    return selections;
  }

  public TableExpression<?> from() {
    return from;
  }

  public Optional<SqlPredicate> where() {
    return Optional.ofNullable(where);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof SelectStatement statement
            && selections.equals(statement.selections)
            && from.equals(statement.from)
            && Objects.equals(where, statement.where);
  }

  @Override
  public int hashCode() {
    int result = selections.hashCode();
    result = 31 * result + from.hashCode();
    return 31 * result + Objects.hashCode(where);
  }

  private static void validateParameterSlots(
      List<SqlExpression<?>> selections, @Nullable SqlPredicate where) {
    Map<Integer, ParameterSlot<?>> slotsByOrdinal = new HashMap<>();
    for (SqlExpression<?> selection : selections) {
      collectParameterSlots(selection, slotsByOrdinal);
    }
    if (where != null) {
      collectParameterSlots(where, slotsByOrdinal);
    }
  }

  private static void collectParameterSlots(
      SqlExpression<?> expression, Map<Integer, ParameterSlot<?>> slotsByOrdinal) {
    switch (expression) {
      case ParameterSlot<?> slot -> validateParameterSlot(slot, slotsByOrdinal);
      case ComparisonPredicate<?> comparison -> {
        collectParameterSlots(comparison.left(), slotsByOrdinal);
        collectParameterSlots(comparison.right(), slotsByOrdinal);
      }
      default -> {
        // Other expression types do not contain parameter slots in the current AST subset.
      }
    }
  }

  private static void validateParameterSlot(
      ParameterSlot<?> slot, Map<Integer, ParameterSlot<?>> slotsByOrdinal) {
    ParameterSlot<?> existing = slotsByOrdinal.putIfAbsent(slot.ordinal(), slot);
    if (existing != null
        && (!existing.javaType().equals(slot.javaType())
            || existing.nullable() != slot.nullable())) {
      throw new IllegalArgumentException(
          "parameter ordinal " + slot.ordinal() + " has conflicting Java type or nullability");
    }
  }
}
