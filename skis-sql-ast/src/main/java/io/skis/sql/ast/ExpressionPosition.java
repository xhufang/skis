package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/** Exact structural position of one expression inside a resolved query block. */
public record ExpressionPosition(
    QueryBlockPath blockPath, QueryClause clause, int itemOrdinal, List<Integer> operandPath) {

  public ExpressionPosition {
    Objects.requireNonNull(blockPath, "blockPath");
    Objects.requireNonNull(clause, "clause");
    if (itemOrdinal < 0) {
      throw new IllegalArgumentException("itemOrdinal must not be negative");
    }
    Objects.requireNonNull(operandPath, "operandPath");
    operandPath = List.copyOf(operandPath);
    for (Integer ordinal : operandPath) {
      if (Objects.requireNonNull(ordinal, "operand ordinal") < 0) {
        throw new IllegalArgumentException("operand ordinal must not be negative");
      }
    }
  }

  /** Returns the position of a nested operand. */
  public ExpressionPosition operand(int ordinal) {
    if (ordinal < 0) {
      throw new IllegalArgumentException("operand ordinal must not be negative");
    }
    java.util.ArrayList<Integer> path = new java.util.ArrayList<>(operandPath.size() + 1);
    path.addAll(operandPath);
    path.add(ordinal);
    return new ExpressionPosition(blockPath, clause, itemOrdinal, path);
  }

  @Override
  public String toString() {
    StringBuilder result =
        new StringBuilder(blockPath.toString())
            .append(' ')
            .append(clause.displayName())
            .append(" item #")
            .append(itemOrdinal);
    for (Integer ordinal : operandPath) {
      result.append(" operand #").append(ordinal);
    }
    return result.toString();
  }
}
