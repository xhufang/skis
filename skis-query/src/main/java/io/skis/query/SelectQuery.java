package io.skis.query;

import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Immutable, composable SELECT model with captured values and resource-free request terminals. */
public interface SelectQuery<R extends @Nullable Object> extends SelectSource<R> {

  SelectQuery<R> where(QueryCondition condition);

  SelectQuery<R> and(QueryCondition condition);

  SelectQuery<R> or(QueryCondition condition);

  /** Declares one ordered, nonempty grouping list; repeating this call fails. */
  SelectQuery<R> groupBy(Selectable<?>... expressions);

  /** Starts HAVING independently of WHERE, with or without explicit GROUP BY. */
  SelectQuery<R> having(QueryCondition condition);

  /** Appends AND to an existing HAVING chain, with left association. */
  SelectQuery<R> andHaving(QueryCondition condition);

  /** Appends OR to an existing HAVING chain, with left association. */
  SelectQuery<R> orHaving(QueryCondition condition);

  JoinOnStep<? extends SelectQuery<R>> join(QueryTable<?> table);

  JoinOnStep<? extends SelectQuery<R>> join(DerivedRelation relation);

  JoinOnStep<? extends SelectQuery<R>> innerJoin(QueryTable<?> table);

  JoinOnStep<? extends SelectQuery<R>> innerJoin(DerivedRelation relation);

  JoinOnStep<? extends SelectQuery<R>> leftJoin(QueryTable<?> table);

  JoinOnStep<? extends SelectQuery<R>> leftJoin(DerivedRelation relation);

  JoinOnStep<? extends SelectQuery<R>> rightJoin(QueryTable<?> table);

  JoinOnStep<? extends SelectQuery<R>> rightJoin(DerivedRelation relation);

  JoinOnStep<? extends SelectQuery<R>> fullJoin(QueryTable<?> table);

  JoinOnStep<? extends SelectQuery<R>> fullJoin(DerivedRelation relation);

  SelectQuery<R> crossJoin(QueryTable<?> table);

  SelectQuery<R> crossJoin(DerivedRelation relation);

  SelectQuery<R> orderBy(SortSpecification... specifications);

  SelectQuery<R> thenByPrimaryKey(SortDirection direction);

  SelectQuery<R> distinct();

  CountQuery countQuery();

  QueryRequest<SingleRow<R>> fetchOne();

  QueryRequest<SingleRow<R>> fetchFirst();

  QueryRequest<List<R>> fetchList();

  QueryRequest<Page<R>> fetchPage(PageRequest request);

  QueryRequest<Page<R>> fetchPage(PageRequest request, CountQuery explicitCount);

  QueryRequest<Slice<R>> fetchSlice(SliceRequest request);

  QueryRequest<QueryCursor<R>> cursor();

  QueryRequest<Stream<R>> stream();
}
