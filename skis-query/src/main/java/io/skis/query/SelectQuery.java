package io.skis.query;

import io.skis.core.ExecutionOptions;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Unified immutable query for entities, scalar values, and generated projections.
 *
 * <p>The result type includes legal SQL nullability. Single-row terminals preserve the difference
 * between no database row and a present row whose selected value is null.
 */
public interface SelectQuery<R extends @Nullable Object> {

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

  /** Starts an INNER JOIN whose ON condition is required before execution is available. */
  JoinOnStep<SelectQuery<R>> join(QueryTable<?> table);

  /** Starts an INNER JOIN against a derived relation. */
  JoinOnStep<SelectQuery<R>> join(DerivedRelation relation);

  /** Starts an explicit INNER JOIN whose ON condition is required. */
  JoinOnStep<SelectQuery<R>> innerJoin(QueryTable<?> table);

  /** Starts an explicit INNER JOIN against a derived relation. */
  JoinOnStep<SelectQuery<R>> innerJoin(DerivedRelation relation);

  /** Starts a LEFT JOIN whose ON condition is required. */
  JoinOnStep<SelectQuery<R>> leftJoin(QueryTable<?> table);

  /** Starts a LEFT JOIN against a derived relation. */
  JoinOnStep<SelectQuery<R>> leftJoin(DerivedRelation relation);

  /** Starts a RIGHT JOIN whose ON condition is required. */
  JoinOnStep<SelectQuery<R>> rightJoin(QueryTable<?> table);

  /** Starts a RIGHT JOIN against a derived relation. */
  JoinOnStep<SelectQuery<R>> rightJoin(DerivedRelation relation);

  /** Starts a FULL JOIN whose ON condition is required. */
  JoinOnStep<SelectQuery<R>> fullJoin(QueryTable<?> table);

  /** Starts a FULL JOIN against a derived relation. */
  JoinOnStep<SelectQuery<R>> fullJoin(DerivedRelation relation);

  /** Appends a CROSS JOIN directly; CROSS JOIN never accepts an ON condition. */
  SelectQuery<R> crossJoin(QueryTable<?> table);

  /** Appends a derived CROSS JOIN directly. */
  SelectQuery<R> crossJoin(DerivedRelation relation);

  SelectQuery<R> withOptions(ExecutionOptions executionOptions);

  /** Replaces the ordering with selectable expressions from the final query scope. */
  SelectQuery<R> orderBy(SortSpecification... specifications);

  /** Appends the actual FROM root's complete primary key to the ordering. */
  SelectQuery<R> thenByPrimaryKey(SortDirection direction);

  /** Applies SQL DISTINCT to the complete visible result tuple. */
  SelectQuery<R> distinct();

  /** Captures one parameter value now and returns an independently executable query. */
  <V> SelectQuery<R> bind(QueryParameter<V> parameter, @Nullable V value);

  /** Creates an independent count descriptor from this query's structural clauses. */
  CountQuery countQuery();

  /** Returns zero or one row, preserving a present SQL null; fails when multiple rows are read. */
  SingleRow<R> fetchOne();

  /** Limits SQL to the first row and preserves both row presence and result nullability. */
  SingleRow<R> fetchFirst();

  /** Materializes all rows, retaining legal null result elements in their original positions. */
  List<R> fetchList();

  Page<R> fetchPage(PageRequest request);

  /** Executes a page with a caller-supplied equivalent count plan. */
  Page<R> fetchPage(PageRequest request, CountQuery explicitCountQuery);

  Slice<R> fetchSlice(SliceRequest request);

  /** Opens a cursor that must be read and closed sequentially on its creating thread. */
  QueryCursor<R> cursor();

  /**
   * Opens a JDBC-backed stream for sequential consumption and closure on its creating thread.
   *
   * <p>Use try-with-resources even for short-circuit terminals. Observed exhaustion completes the
   * resource lifecycle immediately; closing afterward does not emit duplicate completion events.
   */
  Stream<R> stream();
}
