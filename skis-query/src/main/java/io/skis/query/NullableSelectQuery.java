package io.skis.query;

import io.skis.core.ExecutionOptions;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Immutable query whose selected scalar or entity result may be {@code null}. */
public interface NullableSelectQuery<F, R> {

  NullableSelectQuery<F, R> where(QueryPredicate<F> predicate);

  NullableSelectQuery<F, R> where(QueryCondition condition);

  NullableSelectQuery<F, R> and(QueryPredicate<F> predicate);

  NullableSelectQuery<F, R> and(QueryCondition condition);

  NullableSelectQuery<F, R> or(QueryPredicate<F> predicate);

  NullableSelectQuery<F, R> or(QueryCondition condition);

  /** Starts an INNER JOIN whose ON condition is required before execution is available. */
  <J> NullableJoinOnStep<F, R, J> join(QueryTable<J> table);

  /** Starts an explicit INNER JOIN whose ON condition is required. */
  <J> NullableJoinOnStep<F, R, J> innerJoin(QueryTable<J> table);

  /** Starts a LEFT JOIN whose ON condition is required. */
  <J> NullableJoinOnStep<F, R, J> leftJoin(QueryTable<J> table);

  /** Starts a RIGHT JOIN whose ON condition is required. */
  <J> NullableJoinOnStep<F, R, J> rightJoin(QueryTable<J> table);

  /** Starts a FULL JOIN whose ON condition is required. */
  <J> NullableJoinOnStep<F, R, J> fullJoin(QueryTable<J> table);

  /** Appends a CROSS JOIN directly; CROSS JOIN never accepts an ON condition. */
  <J> NullableSelectQuery<F, R> crossJoin(QueryTable<J> table);

  NullableSelectQuery<F, R> withOptions(ExecutionOptions executionOptions);

  /**
   * Replaces the ordering with columns from the final query scope.
   *
   * <p>The ordering column need not belong to the FROM root. Its table occurrence is validated
   * against the completed FROM/JOIN structure before SQL execution.
   */
  NullableSelectQuery<F, R> orderBy(SortSpecification<?>... specifications);

  NullableSelectQuery<F, R> thenByPrimaryKey(SortDirection direction);

  NullableSelectQuery<F, R> distinct();

  /** Creates an independent count plan from this query's source, predicate, and distinct shape. */
  CountQuery countQuery();

  SingleRow<R> fetchOne();

  SingleRow<R> fetchFirst();

  List<@Nullable R> fetchList();

  default List<@Nullable R> fetch() {
    return fetchList();
  }

  Page<@Nullable R> fetchPage(PageRequest request);

  /** Executes a page with a caller-supplied equivalent count plan. */
  Page<@Nullable R> fetchPage(PageRequest request, CountQuery explicitCountQuery);

  Slice<@Nullable R> fetchSlice(SliceRequest request);

  QueryCursor<@Nullable R> cursor();

  CloseableQueryStream<@Nullable R> stream();
}
