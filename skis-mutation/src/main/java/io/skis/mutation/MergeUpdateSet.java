package io.skis.mutation;

import io.skis.query.NonNullSelectable;
import io.skis.query.RowColumns;
import io.skis.query.SelectSource;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnQuery;
import org.jspecify.annotations.Nullable;

/** A target table awaiting at least one typed assignment. */
public interface MergeUpdateSet<E> {

  <V> MergeUpdate<E> set(NonNullSelectable<V> column, V value);

  <V> MergeUpdate<E> set(Selectable<V> column, @Nullable V value);

  <V> MergeUpdate<E> set(NonNullSelectable<V> column, NonNullSelectable<V> value);

  <V> MergeUpdate<E> set(Selectable<V> column, Selectable<V> value);

  <V> MergeUpdate<E> set(Selectable<V> column, SingleColumnQuery<? extends @Nullable V> query);

  <V> MergeUpdate<E> setNull(Selectable<V> column);

  <V> MergeUpdate<E> setDefault(Selectable<V> column);

  MergeUpdate<E> set(RowColumns columns, SelectSource<?> query);
}
