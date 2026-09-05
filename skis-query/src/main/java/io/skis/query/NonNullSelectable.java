package io.skis.query;

/** A selectable whose declared expression value is non-null. */
public sealed interface NonNullSelectable<V> extends Selectable<V> permits NonNullQueryColumn {}
