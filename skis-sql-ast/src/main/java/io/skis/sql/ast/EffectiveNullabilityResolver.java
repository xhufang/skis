package io.skis.sql.ast;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves query-local nullability without mutating reusable SQL expressions. */
final class EffectiveNullabilityResolver {

  private EffectiveNullabilityResolver() {}

  static Nullability resolve(
      SqlExpression<?> expression, Map<TableExpression<?>, Boolean> nullExtendedTables) {
    return resolve(expression, nullExtendedTables, new IdentityHashMap<>());
  }

  static Nullability resolve(
      SqlExpression<?> expression,
      Map<TableExpression<?>, Boolean> nullExtendedTables,
      Map<DerivedRelationReference, Boolean> nullExtendedDerivedRelations) {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(nullExtendedTables, "nullExtendedTables");
    Objects.requireNonNull(nullExtendedDerivedRelations, "nullExtendedDerivedRelations");
    return switch (expression) {
      case ColumnExpression<?, ?> column -> columnNullability(column, nullExtendedTables);
      case DerivedColumnExpression<?> column ->
          derivedColumnNullability(column, nullExtendedDerivedRelations);
      case ParameterSlot<?> parameter -> parameter.nullability();
      case LiteralExpression<?> literal -> literal.nullability();
      case ArithmeticExpression<?> arithmetic ->
          resolve(arithmetic.left(), nullExtendedTables, nullExtendedDerivedRelations)
              .union(resolve(arithmetic.right(), nullExtendedTables, nullExtendedDerivedRelations));
      case ConcatExpression concat ->
          concat.operands().stream()
                  .anyMatch(
                      item ->
                          resolve(item, nullExtendedTables, nullExtendedDerivedRelations)
                              .isNullable())
              ? Nullability.NULLABLE
              : Nullability.NON_NULL;
      case CaseExpression<?> caseExpression ->
          caseNullability(caseExpression, nullExtendedTables, nullExtendedDerivedRelations);
      case CastExpression<?> cast ->
          resolve(cast.operand(), nullExtendedTables, nullExtendedDerivedRelations);
      case CoalesceExpression<?> coalesce ->
          coalesce.operands().stream()
                  .allMatch(
                      item ->
                          resolve(item, nullExtendedTables, nullExtendedDerivedRelations)
                              .isNullable())
              ? Nullability.NULLABLE
              : Nullability.NON_NULL;
      case ComparisonPredicate<?> comparison ->
          resolve(comparison.left(), nullExtendedTables, nullExtendedDerivedRelations)
              .union(resolve(comparison.right(), nullExtendedTables, nullExtendedDerivedRelations));
      case LogicalPredicate logical ->
          logical.operands().stream()
                  .anyMatch(
                      item ->
                          resolve(item, nullExtendedTables, nullExtendedDerivedRelations)
                              .isNullable())
              ? Nullability.NULLABLE
              : Nullability.NON_NULL;
      case NullPredicate ignored -> Nullability.NON_NULL;
      case BetweenPredicate<?> between ->
          resolve(between.value(), nullExtendedTables, nullExtendedDerivedRelations)
              .union(resolve(between.lower(), nullExtendedTables, nullExtendedDerivedRelations))
              .union(resolve(between.upper(), nullExtendedTables, nullExtendedDerivedRelations));
      case LikePredicate like ->
          resolve(like.value(), nullExtendedTables, nullExtendedDerivedRelations)
              .union(resolve(like.pattern(), nullExtendedTables, nullExtendedDerivedRelations));
      case InPredicate<?> in -> inNullability(in, nullExtendedTables, nullExtendedDerivedRelations);
      case InSubqueryPredicate<?> in ->
          inSubqueryNullability(in, nullExtendedTables, nullExtendedDerivedRelations);
      case ExistsPredicate ignored -> Nullability.NON_NULL;
      case ScalarSubqueryExpression<?> ignored -> Nullability.NULLABLE;
      case NotPredicate not ->
          resolve(not.operand(), nullExtendedTables, nullExtendedDerivedRelations);
      case IncrementExpression<?> increment ->
          resolve(increment.operand(), nullExtendedTables, nullExtendedDerivedRelations);
      default ->
          throw new IllegalArgumentException(
              "unsupported SQL expression node " + expression.getClass().getName());
    };
  }

  static IdentityHashMap<TableExpression<?>, Boolean> finalTableState(FromClause fromClause) {
    Objects.requireNonNull(fromClause, "fromClause");
    IdentityHashMap<TableExpression<?>, Boolean> state = new IdentityHashMap<>();
    IdentityHashMap<RelationSource, Boolean> sourceState = finalSourceState(fromClause);
    for (TableOccurrence occurrence : fromClause.occurrences()) {
      TableExpression<?> table = occurrence.entityTable().orElse(null);
      if (table != null) {
        Boolean nullExtended = sourceState.get(occurrence.source());
        if (nullExtended == null) {
          throw new IllegalStateException("relation occurrence has no null-extension state");
        }
        state.put(table, nullExtended);
      }
    }
    return state;
  }

  static IdentityHashMap<DerivedRelationReference, Boolean> finalDerivedRelationState(
      FromClause fromClause) {
    Objects.requireNonNull(fromClause, "fromClause");
    IdentityHashMap<DerivedRelationReference, Boolean> state = new IdentityHashMap<>();
    IdentityHashMap<RelationSource, Boolean> sourceState = finalSourceState(fromClause);
    for (TableOccurrence occurrence : fromClause.occurrences()) {
      DerivedRelationReference reference = occurrence.derivedReference().orElse(null);
      if (reference != null) {
        Boolean nullExtended = sourceState.get(occurrence.source());
        if (nullExtended == null) {
          throw new IllegalStateException("relation occurrence has no null-extension state");
        }
        state.put(reference, nullExtended);
      }
    }
    return state;
  }

  static IdentityHashMap<RelationSource, Boolean> finalSourceState(FromClause fromClause) {
    Objects.requireNonNull(fromClause, "fromClause");
    IdentityHashMap<RelationSource, Boolean> state = new IdentityHashMap<>();
    state.put(fromClause.root(), Boolean.FALSE);
    for (JoinClause join : fromClause.joins()) {
      state.put(join.right(), Boolean.FALSE);
      applyJoin(join.type(), join.right(), state);
    }
    return state;
  }

  static <S> void applyJoin(JoinType type, S right, IdentityHashMap<S, Boolean> state) {
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

  private static <S> void markLeftNullable(S right, IdentityHashMap<S, Boolean> state) {
    for (S source : state.keySet()) {
      if (source != right) {
        state.put(source, Boolean.TRUE);
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

  private static Nullability derivedColumnNullability(
      DerivedColumnExpression<?> column, Map<DerivedRelationReference, Boolean> state) {
    Boolean nullExtended = state.get(column.relation());
    if (nullExtended == null) {
      throw new IllegalArgumentException(
          "derived column '"
              + column.relation().alias().value()
              + '.'
              + column.output().name().value()
              + "' references a relation outside the nullability scope");
    }
    return column.nullable() || nullExtended ? Nullability.NULLABLE : Nullability.NON_NULL;
  }

  private static Nullability caseNullability(
      CaseExpression<?> expression,
      Map<TableExpression<?>, Boolean> tableState,
      Map<DerivedRelationReference, Boolean> derivedState) {
    if (expression.otherwise().isEmpty()
        || resolve(expression.otherwise().orElseThrow(), tableState, derivedState).isNullable()) {
      return Nullability.NULLABLE;
    }
    return expression.branches().stream()
            .anyMatch(branch -> resolve(branch.result(), tableState, derivedState).isNullable())
        ? Nullability.NULLABLE
        : Nullability.NON_NULL;
  }

  private static Nullability inNullability(
      InPredicate<?> expression,
      Map<TableExpression<?>, Boolean> tableState,
      Map<DerivedRelationReference, Boolean> derivedState) {
    if (expression.candidates().isEmpty()) {
      return Nullability.NON_NULL;
    }
    if (resolve(expression.value(), tableState, derivedState).isNullable()) {
      return Nullability.NULLABLE;
    }
    return expression.candidates().stream()
            .anyMatch(candidate -> resolve(candidate, tableState, derivedState).isNullable())
        ? Nullability.NULLABLE
        : Nullability.NON_NULL;
  }

  private static Nullability inSubqueryNullability(
      InSubqueryPredicate<?> expression,
      Map<TableExpression<?>, Boolean> tableState,
      Map<DerivedRelationReference, Boolean> derivedState) {
    IdentityHashMap<TableExpression<?>, Boolean> nestedTableState = new IdentityHashMap<>();
    nestedTableState.putAll(tableState);
    nestedTableState.putAll(finalTableState(expression.subquery().fromClause()));
    IdentityHashMap<DerivedRelationReference, Boolean> nestedDerivedState = new IdentityHashMap<>();
    nestedDerivedState.putAll(derivedState);
    nestedDerivedState.putAll(finalDerivedRelationState(expression.subquery().fromClause()));
    return resolve(expression.value(), tableState, derivedState)
        .union(
            resolve(
                expression.subquery().selections().getFirst(),
                nestedTableState,
                nestedDerivedState));
  }
}
