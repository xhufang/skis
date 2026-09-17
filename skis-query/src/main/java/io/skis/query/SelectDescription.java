package io.skis.query;

import io.skis.sql.ast.JoinType;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, reusable SELECT description with no executor, connection, options, or terminal
 * operations.
 *
 * <p>The base type is conservatively nullable. Framework factories return a more precise subtype
 * when the selected result has a declared non-null contract.
 */
public class SelectDescription<R> {

  private final SelectQueryState<R> state;

  SelectDescription(SelectQueryState<R> state) {
    this.state = Objects.requireNonNull(state, "state");
  }

  /** Returns a new description with its first WHERE condition. */
  public SelectDescription<R> where(QueryCondition condition) {
    return recreate(state.where(QueryConditions.reusableStructure(condition)));
  }

  /** Returns a new description that left-associatively appends an AND condition. */
  public SelectDescription<R> and(QueryCondition condition) {
    return recreate(state.chainWhere(QueryConditions.reusableStructure(condition), true));
  }

  /** Returns a new description that left-associatively appends an OR condition. */
  public SelectDescription<R> or(QueryCondition condition) {
    return recreate(state.chainWhere(QueryConditions.reusableStructure(condition), false));
  }

  /** Starts an INNER JOIN whose ON condition must be supplied. */
  public SelectDescriptionJoinOnStep<R> join(QueryTable<?> table) {
    return innerJoin(table);
  }

  /** Starts an INNER JOIN against a derived relation. */
  public SelectDescriptionJoinOnStep<R> join(DerivedRelation relation) {
    return innerJoin(relation);
  }

  /** Starts an explicit INNER JOIN whose ON condition must be supplied. */
  public SelectDescriptionJoinOnStep<R> innerJoin(QueryTable<?> table) {
    return joinOn(JoinType.INNER, table);
  }

  /** Starts an explicit INNER JOIN against a derived relation. */
  public SelectDescriptionJoinOnStep<R> innerJoin(DerivedRelation relation) {
    return joinOn(JoinType.INNER, relation);
  }

  /** Starts a LEFT JOIN whose ON condition must be supplied. */
  public SelectDescriptionJoinOnStep<R> leftJoin(QueryTable<?> table) {
    return joinOn(JoinType.LEFT, table);
  }

  /** Starts a LEFT JOIN against a derived relation. */
  public SelectDescriptionJoinOnStep<R> leftJoin(DerivedRelation relation) {
    return joinOn(JoinType.LEFT, relation);
  }

  /** Starts a RIGHT JOIN whose ON condition must be supplied. */
  public SelectDescriptionJoinOnStep<R> rightJoin(QueryTable<?> table) {
    return joinOn(JoinType.RIGHT, table);
  }

  /** Starts a RIGHT JOIN against a derived relation. */
  public SelectDescriptionJoinOnStep<R> rightJoin(DerivedRelation relation) {
    return joinOn(JoinType.RIGHT, relation);
  }

  /** Starts a FULL JOIN whose ON condition must be supplied. */
  public SelectDescriptionJoinOnStep<R> fullJoin(QueryTable<?> table) {
    return joinOn(JoinType.FULL, table);
  }

  /** Starts a FULL JOIN against a derived relation. */
  public SelectDescriptionJoinOnStep<R> fullJoin(DerivedRelation relation) {
    return joinOn(JoinType.FULL, relation);
  }

  /** Appends a CROSS JOIN. */
  public SelectDescription<R> crossJoin(QueryTable<?> table) {
    return recreate(state.appendJoin(JoinType.CROSS, table, null));
  }

  /** Appends a derived CROSS JOIN. */
  public SelectDescription<R> crossJoin(DerivedRelation relation) {
    return recreate(state.appendJoin(JoinType.CROSS, relation, null));
  }

  /** Replaces this description's ordering. */
  public SelectDescription<R> orderBy(SortSpecification... specifications) {
    Objects.requireNonNull(specifications, "specifications");
    return recreate(state.orderBy(Arrays.asList(specifications.clone())));
  }

  /** Appends missing root primary-key columns to the ordering. */
  public SelectDescription<R> thenByPrimaryKey(SortDirection direction) {
    return recreate(state.thenByPrimaryKey(direction));
  }

  /** Applies DISTINCT to the complete visible result tuple. */
  public SelectDescription<R> distinct() {
    return recreate(state.distinctResult());
  }

  final SelectQueryState<R> state() {
    return state;
  }

  SelectDescription<R> recreate(SelectQueryState<R> replacement) {
    return replacement == state ? this : new SelectDescription<>(replacement);
  }

  private SelectDescriptionJoinOnStep<R> joinOn(JoinType type, QueryRelation relation) {
    return new SelectDescriptionJoinOnStep<>(
        state, type, Objects.requireNonNull(relation, "relation"));
  }
}
