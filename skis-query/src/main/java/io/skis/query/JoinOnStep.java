package io.skis.query;

/**
 * Required ON stage for a non-CROSS join.
 *
 * <p>This type intentionally exposes no query terminal operation. Calling {@link #on} completes the
 * join and returns a new immutable query.
 */
public interface JoinOnStep<F, R, J> {

  /**
   * Completes the pending join with a condition visible to its accumulated left scope and current
   * right table; later tables are not visible.
   */
  SelectQuery<F, R> on(QueryCondition condition);
}
