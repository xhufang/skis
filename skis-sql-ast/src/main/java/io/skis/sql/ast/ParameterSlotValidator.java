package io.skis.sql.ast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared structural validation for logical parameter slots contained in one statement. */
final class ParameterSlotValidator {

  private ParameterSlotValidator() {}

  static void validate(List<? extends SqlExpression<?>> expressions) {
    Map<Integer, ParameterSlot<?>> slotsByOrdinal = new HashMap<>();
    expressions.forEach(expression -> collect(expression, slotsByOrdinal));
    requireDenseOrdinals(slotsByOrdinal);
  }

  static void validate(
      List<? extends SqlExpression<?>> expressions, SqlExpression<?> trailingExpression) {
    Map<Integer, ParameterSlot<?>> slotsByOrdinal = new HashMap<>();
    expressions.forEach(expression -> collect(expression, slotsByOrdinal));
    collect(trailingExpression, slotsByOrdinal);
    requireDenseOrdinals(slotsByOrdinal);
  }

  private static void collect(
      SqlExpression<?> expression, Map<Integer, ParameterSlot<?>> slotsByOrdinal) {
    switch (expression) {
      case ParameterSlot<?> slot -> validateSlot(slot, slotsByOrdinal);
      case ComparisonPredicate<?> comparison -> {
        collect(comparison.left(), slotsByOrdinal);
        collect(comparison.right(), slotsByOrdinal);
      }
      case LogicalPredicate logical ->
          logical.operands().forEach(operand -> collect(operand, slotsByOrdinal));
      case IncrementExpression<?> increment -> collect(increment.operand(), slotsByOrdinal);
      default -> {
        // Other expression types contain no parameter slots in the current AST subset.
      }
    }
  }

  private static void validateSlot(
      ParameterSlot<?> slot, Map<Integer, ParameterSlot<?>> slotsByOrdinal) {
    ParameterSlot<?> existing = slotsByOrdinal.putIfAbsent(slot.ordinal(), slot);
    if (existing != null
        && (!existing.javaType().equals(slot.javaType())
            || existing.nullable() != slot.nullable())) {
      throw new IllegalArgumentException(
          "parameter ordinal " + slot.ordinal() + " has conflicting Java type or nullability");
    }
  }

  private static void requireDenseOrdinals(Map<Integer, ParameterSlot<?>> slotsByOrdinal) {
    for (int expected = 0; expected < slotsByOrdinal.size(); expected++) {
      if (!slotsByOrdinal.containsKey(expected)) {
        throw new IllegalArgumentException(
            "parameter ordinals must be contiguous from zero; missing ordinal " + expected);
      }
    }
  }
}
