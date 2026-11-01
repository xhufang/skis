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
public interface ExecutableSelectQuery<R extends @Nullable Object> extends SelectSource<R> {

  ExecutableSelectQuery<R> where(QueryCondition condition);

  ExecutableSelectQuery<R> and(QueryCondition condition);

  ExecutableSelectQuery<R> or(QueryCondition condition);

  /** Declares one ordered, nonempty grouping list; repeating this call fails. */
  ExecutableSelectQuery<R> groupBy(Selectable<?>... expressions);

  /** Starts HAVING independently of WHERE, with or without explicit GROUP BY. */
  ExecutableSelectQuery<R> having(QueryCondition condition);

  /** Appends AND to an existing HAVING chain, with left association. */
  ExecutableSelectQuery<R> andHaving(QueryCondition condition);

  /** Appends OR to an existing HAVING chain, with left association. */
  ExecutableSelectQuery<R> orHaving(QueryCondition condition);

  /** Starts an INNER JOIN whose ON condition is required before execution is available. */
  JoinOnStep<? extends ExecutableSelectQuery<R>> join(QueryTable<?> table);

  /** Starts an INNER JOIN against a derived relation. */
  JoinOnStep<? extends ExecutableSelectQuery<R>> join(DerivedRelation relation);

  /** Starts an explicit INNER JOIN whose ON condition is required. */
  JoinOnStep<? extends ExecutableSelectQuery<R>> innerJoin(QueryTable<?> table);

  /** Starts an explicit INNER JOIN against a derived relation. */
  JoinOnStep<? extends ExecutableSelectQuery<R>> innerJoin(DerivedRelation relation);

  /** Starts a LEFT JOIN whose ON condition is required. */
  JoinOnStep<? extends ExecutableSelectQuery<R>> leftJoin(QueryTable<?> table);

  /** Starts a LEFT JOIN against a derived relation. */
  JoinOnStep<? extends ExecutableSelectQuery<R>> leftJoin(DerivedRelation relation);

  /** Starts a RIGHT JOIN whose ON condition is required. */
  JoinOnStep<? extends ExecutableSelectQuery<R>> rightJoin(QueryTable<?> table);

  /** Starts a RIGHT JOIN against a derived relation. */
  JoinOnStep<? extends ExecutableSelectQuery<R>> rightJoin(DerivedRelation relation);

  /** Starts a FULL JOIN whose ON condition is required. */
  JoinOnStep<? extends ExecutableSelectQuery<R>> fullJoin(QueryTable<?> table);

  /** Starts a FULL JOIN against a derived relation. */
  JoinOnStep<? extends ExecutableSelectQuery<R>> fullJoin(DerivedRelation relation);

  /** Appends a CROSS JOIN directly; CROSS JOIN never accepts an ON condition. */
  ExecutableSelectQuery<R> crossJoin(QueryTable<?> table);

  /** Appends a derived CROSS JOIN directly. */
  ExecutableSelectQuery<R> crossJoin(DerivedRelation relation);

  ExecutableSelectQuery<R> withOptions(ExecutionOptions executionOptions);

  /** Replaces the ordering with selectable expressions from the final query scope. */
  ExecutableSelectQuery<R> orderBy(SortSpecification... specifications);

  /** Appends the actual FROM root's complete primary key to the ordering. */
  ExecutableSelectQuery<R> thenByPrimaryKey(SortDirection direction);

  /** Applies SQL DISTINCT to the complete visible result tuple. */
  ExecutableSelectQuery<R> distinct();

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

  /**
   * Opens a cursor for sequential reads on its creating thread. The dialect must support
   * incremental reads; a positive fetch size, autoCommit=false and a supported driver mode are
   * required.
   */
  QueryCursor<R> cursor();

  /**
   * Opens a JDBC-backed stream for sequential consumption and closure on its creating thread.
   *
   * <p>Use try-with-resources even for short-circuit terminals. Observed exhaustion completes the
   * resource lifecycle immediately; closing afterward does not emit duplicate completion events.
   * The same driver and transaction preconditions as {@link #cursor()} apply.
   */
  Stream<R> stream();
}
