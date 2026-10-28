package io.skis.mutation;

import io.skis.query.NonNullSelectable;
import io.skis.query.Selectable;
import org.jspecify.annotations.Nullable;

/** A target table awaiting at least one typed assignment. */
public interface UpdateSet<E> {

  <V> UpdateWrite<E> set(NonNullSelectable<V> column, V value);

  <V> UpdateWrite<E> set(Selectable<V> column, @Nullable V value);

  <V> UpdateWrite<E> setExpression(NonNullSelectable<V> column, NonNullSelectable<V> value);

  <V> UpdateWrite<E> setExpression(Selectable<V> column, Selectable<V> value);
}
