package io.skis.query;

import io.skis.core.ExecutionOptions;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Immutable query whose selected scalar or entity result may be {@code null}. */
public interface NullableSelectQuery<F, R> {

  NullableSelectQuery<F, R> where(QueryCondition condition);

  NullableSelectQuery<F, R> and(QueryCondition condition);

  NullableSelectQuery<F, R> or(QueryCondition condition);

  /** Starts an INNER JOIN whose ON condition is required before execution is available. */
  NullableJoinOnStep<F, R> join(QueryTable<?> table);

  /** Starts an INNER JOIN against a derived relation. */
  NullableJoinOnStep<F, R> join(DerivedRelation relation);

  /** Starts an explicit INNER JOIN whose ON condition is required. */
  NullableJoinOnStep<F, R> innerJoin(QueryTable<?> table);

  /** Starts an explicit INNER JOIN against a derived relation. */
  NullableJoinOnStep<F, R> innerJoin(DerivedRelation relation);

  /** Starts a LEFT JOIN whose ON condition is required. */
  NullableJoinOnStep<F, R> leftJoin(QueryTable<?> table);

  /** Starts a LEFT JOIN against a derived relation. */
  NullableJoinOnStep<F, R> leftJoin(DerivedRelation relation);

  /** Starts a RIGHT JOIN whose ON condition is required. */
  NullableJoinOnStep<F, R> rightJoin(QueryTable<?> table);

  /** Starts a RIGHT JOIN against a derived relation. */
  NullableJoinOnStep<F, R> rightJoin(DerivedRelation relation);

  /** Starts a FULL JOIN whose ON condition is required. */
  NullableJoinOnStep<F, R> fullJoin(QueryTable<?> table);

  /** Starts a FULL JOIN against a derived relation. */
  NullableJoinOnStep<F, R> fullJoin(DerivedRelation relation);

  /** Appends a CROSS JOIN directly; CROSS JOIN never accepts an ON condition. */
  NullableSelectQuery<F, R> crossJoin(QueryTable<?> table);

  /** Appends a derived CROSS JOIN directly. */
  NullableSelectQuery<F, R> crossJoin(DerivedRelation relation);

  NullableSelectQuery<F, R> withOptions(ExecutionOptions executionOptions);

  /**
   * Replaces the ordering with selectable expressions from the final query scope.
   *
   * <p>An ordering expression need not belong to the FROM root. Its dependencies are validated
   * against the completed FROM/JOIN structure before SQL execution.
   */
  NullableSelectQuery<F, R> orderBy(SortSpecification... specifications);

  /**
   * Appends the FROM root's complete primary key to the ordering.
   *
   * <p>For paginated Joins, the caller must still order by the required keys of other participating
   * table occurrences.
   */
  NullableSelectQuery<F, R> thenByPrimaryKey(SortDirection direction);

  /** Applies SQL DISTINCT to the complete visible result tuple, including one possible NULL row. */
  NullableSelectQuery<F, R> distinct();

  /**
   * Creates an independent count descriptor from this query's FROM/JOIN/ON/WHERE and distinct
   * shape.
   *
   * <p>The count is validated and compiled when it is supplied to a page operation. A distinct
   * result with no portable equivalent count is rejected at that point.
   */
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
