package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.query.DerivedRelation;
import io.skis.query.JoinOnStep;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;
import io.skis.query.SelectDescription;
import io.skis.query.SortDirection;
import io.skis.query.SortSpecification;
import io.skis.sql.ast.JoinType;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable, reusable SELECT structure with no executor, options, or call-time values. */
class DefaultSelectDescription<R extends @Nullable Object> implements SelectDescription<R> {

  private final SelectQueryState<R> state;

  DefaultSelectDescription(SelectQueryState<R> state) {
    this.state = Objects.requireNonNull(state, "state");
  }

  public SelectDescription<R> where(QueryCondition condition) {
    return recreate(state.where(QueryConditions.reusableStructure(condition)));
  }

  public SelectDescription<R> and(QueryCondition condition) {
    return recreate(state.chainWhere(QueryConditions.reusableStructure(condition), true));
  }

  public SelectDescription<R> or(QueryCondition condition) {
    return recreate(state.chainWhere(QueryConditions.reusableStructure(condition), false));
  }

  public JoinOnStep<? extends SelectDescription<R>> join(QueryTable<?> table) {
    return innerJoin(table);
  }

  public JoinOnStep<? extends SelectDescription<R>> join(DerivedRelation relation) {
    return innerJoin(relation);
  }

  public JoinOnStep<? extends SelectDescription<R>> innerJoin(QueryTable<?> table) {
    return joinOn(JoinType.INNER, QueryRelations.of(table));
  }

  public JoinOnStep<? extends SelectDescription<R>> innerJoin(DerivedRelation relation) {
    return joinOn(JoinType.INNER, QueryRelations.of(relation));
  }

  public JoinOnStep<? extends SelectDescription<R>> leftJoin(QueryTable<?> table) {
    return joinOn(JoinType.LEFT, QueryRelations.of(table));
  }

  public JoinOnStep<? extends SelectDescription<R>> leftJoin(DerivedRelation relation) {
    return joinOn(JoinType.LEFT, QueryRelations.of(relation));
  }

  public JoinOnStep<? extends SelectDescription<R>> rightJoin(QueryTable<?> table) {
    return joinOn(JoinType.RIGHT, QueryRelations.of(table));
  }

  public JoinOnStep<? extends SelectDescription<R>> rightJoin(DerivedRelation relation) {
    return joinOn(JoinType.RIGHT, QueryRelations.of(relation));
  }

  public JoinOnStep<? extends SelectDescription<R>> fullJoin(QueryTable<?> table) {
    return joinOn(JoinType.FULL, QueryRelations.of(table));
  }

  public JoinOnStep<? extends SelectDescription<R>> fullJoin(DerivedRelation relation) {
    return joinOn(JoinType.FULL, QueryRelations.of(relation));
  }

  public SelectDescription<R> crossJoin(QueryTable<?> table) {
    return recreate(state.appendJoin(JoinType.CROSS, QueryRelations.of(table), null));
  }

  public SelectDescription<R> crossJoin(DerivedRelation relation) {
    return recreate(state.appendJoin(JoinType.CROSS, QueryRelations.of(relation), null));
  }

  public SelectDescription<R> orderBy(SortSpecification... specifications) {
    Objects.requireNonNull(specifications, "specifications");
    return recreate(state.orderBy(Arrays.asList(specifications.clone())));
  }

  public SelectDescription<R> thenByPrimaryKey(SortDirection direction) {
    return recreate(state.thenByPrimaryKey(direction));
  }

  public SelectDescription<R> distinct() {
    return recreate(state.distinctResult());
  }

  final SelectQueryState<R> state() {
    return state;
  }

  SelectDescription<R> recreate(SelectQueryState<R> replacement) {
    return replacement == state ? this : new DefaultSelectDescription<>(replacement);
  }

  private JoinOnStep<SelectDescription<R>> joinOn(JoinType type, QueryRelation relation) {
    return new DescriptionJoinOnStep<>(state, type, Objects.requireNonNull(relation, "relation"));
  }
}

/** Internal ON stage for reusable descriptions. */
final class DescriptionJoinOnStep<R extends @Nullable Object>
    implements JoinOnStep<SelectDescription<R>> {

  private final SelectQueryState<R> state;
  private final JoinType type;
  private final QueryRelation relation;

  DescriptionJoinOnStep(SelectQueryState<R> state, JoinType type, QueryRelation relation) {
    this.state = Objects.requireNonNull(state, "state");
    this.type = Objects.requireNonNull(type, "type");
    if (type == JoinType.CROSS) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_JOIN_CONTRACT, "CROSS JOIN does not use an ON stage");
    }
    this.relation = Objects.requireNonNull(relation, "relation");
  }

  @Override
  public SelectDescription<R> on(QueryCondition condition) {
    QueryCondition reusable = QueryConditions.reusableStructure(condition);
    return new DefaultSelectDescription<>(state.appendJoin(type, relation, reusable));
  }
}
