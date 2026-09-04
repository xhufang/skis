package io.skis.query;

import io.skis.core.ExecutionOptions;
import java.util.List;
import java.util.Optional;

/** Unified immutable query for entities, non-null scalars, and generated projections. */
public interface SelectQuery<E, R> {

  SelectQuery<E, R> where(QueryPredicate<E> predicate);

  SelectQuery<E, R> where(QueryCondition condition);

  SelectQuery<E, R> and(QueryPredicate<E> predicate);

  SelectQuery<E, R> and(QueryCondition condition);

  SelectQuery<E, R> or(QueryPredicate<E> predicate);

  SelectQuery<E, R> or(QueryCondition condition);

  /** Starts an INNER JOIN whose ON condition is required before query execution is available. */
  <J> JoinOnStep<E, R, J> join(QueryTable<J> table);

  /** Starts an explicit INNER JOIN whose ON condition is required. */
  <J> JoinOnStep<E, R, J> innerJoin(QueryTable<J> table);

  /** Starts a LEFT JOIN whose ON condition is required. */
  <J> JoinOnStep<E, R, J> leftJoin(QueryTable<J> table);

  /** Starts a RIGHT JOIN whose ON condition is required. */
  <J> JoinOnStep<E, R, J> rightJoin(QueryTable<J> table);

  /** Starts a FULL JOIN whose ON condition is required. */
  <J> JoinOnStep<E, R, J> fullJoin(QueryTable<J> table);

  /** Appends a CROSS JOIN directly; CROSS JOIN never accepts an ON condition. */
  <J> SelectQuery<E, R> crossJoin(QueryTable<J> table);

  SelectQuery<E, R> withOptions(ExecutionOptions executionOptions);

  @SuppressWarnings("unchecked")
  SelectQuery<E, R> orderBy(SortSpecification<E>... specifications);

  SelectQuery<E, R> thenByPrimaryKey(SortDirection direction);

  SelectQuery<E, R> distinct();

  /** Creates an independent count plan from this query's source, predicate, and distinct shape. */
  CountQuery countQuery();

  Optional<R> fetchOne();

  Optional<R> fetchFirst();

  List<R> fetchList();

  default List<R> fetch() {
    return fetchList();
  }

  Page<R> fetchPage(PageRequest request);

  /** Executes a page with a caller-supplied equivalent count plan. */
  Page<R> fetchPage(PageRequest request, CountQuery explicitCountQuery);

  Slice<R> fetchSlice(SliceRequest request);

  QueryCursor<R> cursor();

  CloseableQueryStream<R> stream();
}
