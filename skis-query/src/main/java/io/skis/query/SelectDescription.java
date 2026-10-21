package io.skis.query;

import org.jspecify.annotations.Nullable;

/** Immutable, reusable SELECT structure with no executor, options, or call-time values. */
public interface SelectDescription<R extends @Nullable Object> {

  SelectDescription<R> where(QueryCondition condition);

  SelectDescription<R> and(QueryCondition condition);

  SelectDescription<R> or(QueryCondition condition);

  JoinOnStep<? extends SelectDescription<R>> join(QueryTable<?> table);

  JoinOnStep<? extends SelectDescription<R>> join(DerivedRelation relation);

  JoinOnStep<? extends SelectDescription<R>> innerJoin(QueryTable<?> table);

  JoinOnStep<? extends SelectDescription<R>> innerJoin(DerivedRelation relation);

  JoinOnStep<? extends SelectDescription<R>> leftJoin(QueryTable<?> table);

  JoinOnStep<? extends SelectDescription<R>> leftJoin(DerivedRelation relation);

  JoinOnStep<? extends SelectDescription<R>> rightJoin(QueryTable<?> table);

  JoinOnStep<? extends SelectDescription<R>> rightJoin(DerivedRelation relation);

  JoinOnStep<? extends SelectDescription<R>> fullJoin(QueryTable<?> table);

  JoinOnStep<? extends SelectDescription<R>> fullJoin(DerivedRelation relation);

  SelectDescription<R> crossJoin(QueryTable<?> table);

  SelectDescription<R> crossJoin(DerivedRelation relation);

  SelectDescription<R> orderBy(SortSpecification... specifications);

  SelectDescription<R> thenByPrimaryKey(SortDirection direction);

  SelectDescription<R> distinct();
}
