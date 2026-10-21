package io.skis.query;

import org.jspecify.annotations.Nullable;

/** Reusable SELECT description whose final visible SQL shape contains exactly one value. */
public interface SingleColumnSelect<R extends @Nullable Object> extends SelectDescription<R> {

  SingleColumnSelect<R> where(QueryCondition condition);

  SingleColumnSelect<R> and(QueryCondition condition);

  SingleColumnSelect<R> or(QueryCondition condition);

  JoinOnStep<SingleColumnSelect<R>> join(QueryTable<?> table);

  JoinOnStep<SingleColumnSelect<R>> join(DerivedRelation relation);

  JoinOnStep<SingleColumnSelect<R>> innerJoin(QueryTable<?> table);

  JoinOnStep<SingleColumnSelect<R>> innerJoin(DerivedRelation relation);

  JoinOnStep<SingleColumnSelect<R>> leftJoin(QueryTable<?> table);

  JoinOnStep<SingleColumnSelect<R>> leftJoin(DerivedRelation relation);

  JoinOnStep<SingleColumnSelect<R>> rightJoin(QueryTable<?> table);

  JoinOnStep<SingleColumnSelect<R>> rightJoin(DerivedRelation relation);

  JoinOnStep<SingleColumnSelect<R>> fullJoin(QueryTable<?> table);

  JoinOnStep<SingleColumnSelect<R>> fullJoin(DerivedRelation relation);

  SingleColumnSelect<R> crossJoin(QueryTable<?> table);

  SingleColumnSelect<R> crossJoin(DerivedRelation relation);

  SingleColumnSelect<R> orderBy(SortSpecification... specifications);

  SingleColumnSelect<R> thenByPrimaryKey(SortDirection direction);

  SingleColumnSelect<R> distinct();
}
