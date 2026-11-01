package io.skis.query;

import java.util.List;

/** Framework-owned reusable SELECT source with an explicit, typed public output shape. */
public interface DerivedRelation {

  /** Explicit relation alias used to qualify every output column. */
  String alias();

  /** Ordered immutable output shape. */
  List<DerivedOutput<?>> outputs();

  /** Creates another occurrence over the same SELECT and handles with a different alias. */
  DerivedRelation as(String alias);

  /** Resolves an expression selected by this source; missing or ambiguous selections fail. */
  <V> Selectable<V> column(Selectable<V> expression);

  /** Preserves non-nullness only if it holds in the complete inner query. */
  <V> NonNullSelectable<V> column(NonNullSelectable<V> expression);

  /** Allows null extension of an originally non-null selected expression. */
  <V> Selectable<V> columnNullable(NonNullSelectable<V> expression);

  /** Resolves one nullable output handle that belongs to this relation shape. */
  <V> Selectable<V> column(DerivedOutput<V> output);

  /** Resolves one non-null output handle that belongs to this relation shape. */
  <V> NonNullSelectable<V> column(NonNullDerivedOutput<V> output);
}
