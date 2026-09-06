package io.skis.query;

/** Required ON stage for a join whose selected result may be {@code null}. */
public interface NullableJoinOnStep<F, R, J> {

  /**
   * Completes the pending join with a condition visible to its accumulated left scope and current
   * right table; later tables are not visible.
   */
  NullableSelectQuery<F, R> on(QueryCondition condition);
}
