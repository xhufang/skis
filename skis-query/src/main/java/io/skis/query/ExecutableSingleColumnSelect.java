package io.skis.query;

import org.jspecify.annotations.Nullable;

/** Reusable SELECT description whose final visible SQL shape contains exactly one value. */
public interface ExecutableSingleColumnSelect<R extends @Nullable Object>
    extends ExecutableSelectQuery<R>, SingleColumnQuery<R> {

  ExecutableSingleColumnSelect<R> where(QueryCondition condition);

  ExecutableSingleColumnSelect<R> and(QueryCondition condition);

  ExecutableSingleColumnSelect<R> or(QueryCondition condition);

  /** Declares one ordered, nonempty grouping list; repeating this call fails. */
  ExecutableSingleColumnSelect<R> groupBy(Selectable<?>... expressions);

  /** Starts HAVING independently of WHERE, with or without explicit GROUP BY. */
  ExecutableSingleColumnSelect<R> having(QueryCondition condition);

  /** Appends AND to an existing HAVING chain, with left association. */
  ExecutableSingleColumnSelect<R> andHaving(QueryCondition condition);

  /** Appends OR to an existing HAVING chain, with left association. */
  ExecutableSingleColumnSelect<R> orHaving(QueryCondition condition);

  JoinOnStep<ExecutableSingleColumnSelect<R>> join(QueryTable<?> table);

  JoinOnStep<ExecutableSingleColumnSelect<R>> join(DerivedRelation relation);

  JoinOnStep<ExecutableSingleColumnSelect<R>> innerJoin(QueryTable<?> table);

  JoinOnStep<ExecutableSingleColumnSelect<R>> innerJoin(DerivedRelation relation);

  JoinOnStep<ExecutableSingleColumnSelect<R>> leftJoin(QueryTable<?> table);

  JoinOnStep<ExecutableSingleColumnSelect<R>> leftJoin(DerivedRelation relation);

  JoinOnStep<ExecutableSingleColumnSelect<R>> rightJoin(QueryTable<?> table);

  JoinOnStep<ExecutableSingleColumnSelect<R>> rightJoin(DerivedRelation relation);

  JoinOnStep<ExecutableSingleColumnSelect<R>> fullJoin(QueryTable<?> table);

  JoinOnStep<ExecutableSingleColumnSelect<R>> fullJoin(DerivedRelation relation);

  ExecutableSingleColumnSelect<R> crossJoin(QueryTable<?> table);

  ExecutableSingleColumnSelect<R> crossJoin(DerivedRelation relation);

  ExecutableSingleColumnSelect<R> orderBy(SortSpecification... specifications);

  ExecutableSingleColumnSelect<R> thenByPrimaryKey(SortDirection direction);

  ExecutableSingleColumnSelect<R> distinct();

  ExecutableSingleColumnSelect<R> withOptions(io.skis.core.ExecutionOptions options);
}
