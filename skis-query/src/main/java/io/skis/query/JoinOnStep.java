package io.skis.query;

/** Required ON stage for a non-CROSS join; it exposes no terminal query operation. */
public interface JoinOnStep<Q> {

  /** Completes the pending join and returns a new immutable query or description. */
  Q on(QueryCondition condition);
}
