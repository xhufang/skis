package io.skis.query;

/**
 * Required ON stage for a non-CROSS join.
 *
 * <p>This type intentionally exposes no query terminal operation. Calling {@link #on} completes the
 * join and returns a new immutable query.
 */
public interface JoinOnStep<F, R, J> {

  /** Completes the pending join with a condition visible to its current left and right tables. */
  SelectQuery<F, R> on(QueryCondition condition);
}
