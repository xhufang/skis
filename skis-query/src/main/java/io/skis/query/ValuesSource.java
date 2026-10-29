package io.skis.query;

/** One typed VALUES row occurrence for MERGE USING; values are invocation-owned. */
public interface ValuesSource {

  ValuesSource as(String alias);

  <V> Selectable<V> column(DerivedOutput<V> output);

  <V> NonNullSelectable<V> column(NonNullDerivedOutput<V> output);
}
