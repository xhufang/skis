package io.skis.query;

import org.jspecify.annotations.Nullable;

/** Immutable, reusable SELECT structure with no executor, options, or call-time values. */
public interface SelectDescription<R extends @Nullable Object> {

  SelectDescription<R> where(QueryCondition condition);

  SelectDescription<R> and(QueryCondition condition);

  SelectDescription<R> or(QueryCondition condition);

  /** Declares one ordered, nonempty grouping list; repeating this call fails. */
  SelectDescription<R> groupBy(Selectable<?>... expressions);

  /** Starts HAVING independently of WHERE, with or without explicit GROUP BY. */
  SelectDescription<R> having(QueryCondition condition);

  /** Appends AND to an existing HAVING chain, with left association. */
  SelectDescription<R> andHaving(QueryCondition condition);

  /** Appends OR to an existing HAVING chain, with left association. */
  SelectDescription<R> orHaving(QueryCondition condition);

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
