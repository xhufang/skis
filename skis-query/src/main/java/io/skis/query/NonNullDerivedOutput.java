package io.skis.query;

/** Explicit derived-output handle whose completed inner expression is required to be non-null. */
public interface NonNullDerivedOutput<V> extends DerivedOutput<V> {}
