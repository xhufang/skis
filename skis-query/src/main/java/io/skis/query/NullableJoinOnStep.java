package io.skis.query;

/** Required ON stage for a join whose selected result may be {@code null}. */
public interface NullableJoinOnStep<F, R, J> {

  /** Completes the pending join with a condition visible to its current left and right tables. */
  NullableSelectQuery<F, R> on(QueryCondition condition);
}
