package io.skis.mutation;

import io.skis.query.NonNullSelectable;
import io.skis.query.Selectable;
import org.jspecify.annotations.Nullable;

/** Callback-scoped collector of typed columns and ordinary values. */
public interface ValueRow {

  <V> ValueRow value(NonNullSelectable<V> column, V value);

  <V> ValueRow value(Selectable<V> column, @Nullable V value);
}
