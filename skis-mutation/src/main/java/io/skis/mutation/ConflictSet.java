package io.skis.mutation;

import io.skis.query.NonNullSelectable;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnQuery;
import org.jspecify.annotations.Nullable;

/** A target table awaiting at least one typed assignment. */
public interface ConflictSet<E> {

  <V> ConflictUpdate<E> set(NonNullSelectable<V> column, V value);

  <V> ConflictUpdate<E> set(Selectable<V> column, @Nullable V value);

  <V> ConflictUpdate<E> set(NonNullSelectable<V> column, NonNullSelectable<V> value);

  <V> ConflictUpdate<E> set(Selectable<V> column, Selectable<V> value);

  <V> ConflictUpdate<E> set(Selectable<V> column, SingleColumnQuery<? extends @Nullable V> query);

  <V> ConflictUpdate<E> setNull(Selectable<V> column);

  <V> ConflictUpdate<E> setDefault(Selectable<V> column);
}
