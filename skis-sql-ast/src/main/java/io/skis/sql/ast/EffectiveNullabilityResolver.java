package io.skis.sql.ast;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves query-local nullability without mutating reusable SQL expressions. */
final class EffectiveNullabilityResolver {

  private EffectiveNullabilityResolver() {}

  static Nullability resolve(
      SqlExpression<?> expression, Map<TableExpression<?>, Boolean> nullExtendedTables) {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(nullExtendedTables, "nullExtendedTables");
    return switch (expression) {
      case ColumnExpression<?, ?> column -> columnNullability(column, nullExtendedTables);
      case ParameterSlot<?> parameter -> parameter.nullability();
      case LiteralExpression<?> literal -> literal.nullability();
      case ArithmeticExpression<?> arithmetic ->
          resolve(arithmetic.left(), nullExtendedTables)
              .union(resolve(arithmetic.right(), nullExtendedTables));
      case ConcatExpression concat ->
          concat.operands().stream()
                  .anyMatch(item -> resolve(item, nullExtendedTables).isNullable())
              ? Nullability.NULLABLE
              : Nullability.NON_NULL;
      case CaseExpression<?> caseExpression -> caseNullability(caseExpression, nullExtendedTables);
      case CastExpression<?> cast -> resolve(cast.operand(), nullExtendedTables);
      case CoalesceExpression<?> coalesce ->
          coalesce.operands().stream()
                  .allMatch(item -> resolve(item, nullExtendedTables).isNullable())
              ? Nullability.NULLABLE
              : Nullability.NON_NULL;
      case ComparisonPredicate<?> comparison ->
          resolve(comparison.left(), nullExtendedTables)
              .union(resolve(comparison.right(), nullExtendedTables));
      case LogicalPredicate logical ->
          logical.operands().stream()
                  .anyMatch(item -> resolve(item, nullExtendedTables).isNullable())
              ? Nullability.NULLABLE
              : Nullability.NON_NULL;
      case NullPredicate ignored -> Nullability.NON_NULL;
      case BetweenPredicate<?> between ->
          resolve(between.value(), nullExtendedTables)
              .union(resolve(between.lower(), nullExtendedTables))
              .union(resolve(between.upper(), nullExtendedTables));
      case LikePredicate like ->
          resolve(like.value(), nullExtendedTables)
              .union(resolve(like.pattern(), nullExtendedTables));
      case InPredicate<?> in -> inNullability(in, nullExtendedTables);
      case NotPredicate not -> resolve(not.operand(), nullExtendedTables);
      case IncrementExpression<?> increment -> resolve(increment.operand(), nullExtendedTables);
      default -> expression.nullability();
    };
  }

  static IdentityHashMap<TableExpression<?>, Boolean> finalTableState(FromClause fromClause) {
    Objects.requireNonNull(fromClause, "fromClause");
    IdentityHashMap<TableExpression<?>, Boolean> state = new IdentityHashMap<>();
    state.put(fromClause.root(), Boolean.FALSE);
    for (JoinClause join : fromClause.joins()) {
      state.put(join.right(), Boolean.FALSE);
      applyJoin(join.type(), join.right(), state);
    }
    return state;
  }

  static void applyJoin(
      JoinType type, TableExpression<?> right, IdentityHashMap<TableExpression<?>, Boolean> state) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(right, "right");
    Objects.requireNonNull(state, "state");
    switch (type) {
      case INNER, CROSS -> {}
      case LEFT -> state.put(right, Boolean.TRUE);
      case RIGHT -> markLeftNullable(right, state);
      case FULL -> {
        markLeftNullable(right, state);
        state.put(right, Boolean.TRUE);
      }
    }
  }

  private static void markLeftNullable(
      TableExpression<?> right, IdentityHashMap<TableExpression<?>, Boolean> state) {
    for (TableExpression<?> table : state.keySet()) {
      if (table != right) {
        state.put(table, Boolean.TRUE);
      }
    }
  }

  private static Nullability columnNullability(
      ColumnExpression<?, ?> column, Map<TableExpression<?>, Boolean> state) {
    Boolean nullExtended = state.get(column.table());
    if (nullExtended == null) {
      throw new IllegalArgumentException(
          "column '"
              + column.property().name()
              + "' references a table outside the nullability scope");
    }
    return column.nullable() || nullExtended ? Nullability.NULLABLE : Nullability.NON_NULL;
  }

  private static Nullability caseNullability(
      CaseExpression<?> expression, Map<TableExpression<?>, Boolean> state) {
    if (expression.otherwise().isEmpty()
        || resolve(expression.otherwise().orElseThrow(), state).isNullable()) {
      return Nullability.NULLABLE;
    }
    return expression.branches().stream()
            .anyMatch(branch -> resolve(branch.result(), state).isNullable())
        ? Nullability.NULLABLE
        : Nullability.NON_NULL;
  }

  private static Nullability inNullability(
      InPredicate<?> expression, Map<TableExpression<?>, Boolean> state) {
    if (expression.candidates().isEmpty()) {
      return Nullability.NON_NULL;
    }
    if (resolve(expression.value(), state).isNullable()) {
      return Nullability.NULLABLE;
    }
    return expression.candidates().stream()
            .anyMatch(candidate -> resolve(candidate, state).isNullable())
        ? Nullability.NULLABLE
        : Nullability.NON_NULL;
  }
}
