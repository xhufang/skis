package io.skis.mutation;

import io.skis.query.NonNullSelectable;
import io.skis.query.Selectable;
import org.jspecify.annotations.Nullable;

/** A target table awaiting at least one typed assignment. */
public interface MergeUpdateSet<E> {

  <V> MergeUpdate<E> set(NonNullSelectable<V> column, V value);

  <V> MergeUpdate<E> set(Selectable<V> column, @Nullable V value);

  <V> MergeUpdate<E> setExpression(NonNullSelectable<V> column, NonNullSelectable<V> value);

  <V> MergeUpdate<E> setExpression(Selectable<V> column, Selectable<V> value);
}
