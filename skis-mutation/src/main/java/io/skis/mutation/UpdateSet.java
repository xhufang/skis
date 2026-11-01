package io.skis.mutation;

import io.skis.query.DerivedRelation;
import io.skis.query.NonNullSelectable;
import io.skis.query.QueryTable;
import io.skis.query.RowColumns;
import io.skis.query.SelectSource;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnQuery;
import org.jspecify.annotations.Nullable;

/** A target table awaiting at least one typed assignment. */
public interface UpdateSet<E> {

  UpdateSet<E> from(QueryTable<?> source);

  UpdateSet<E> from(DerivedRelation source);

  <V> UpdateWrite<E> set(NonNullSelectable<V> column, V value);

  <V> UpdateWrite<E> set(Selectable<V> column, @Nullable V value);

  <V> UpdateWrite<E> set(NonNullSelectable<V> column, NonNullSelectable<V> value);

  <V> UpdateWrite<E> set(Selectable<V> column, Selectable<V> value);

  <V> UpdateWrite<E> set(Selectable<V> column, SingleColumnQuery<? extends @Nullable V> query);

  <V> UpdateWrite<E> setNull(Selectable<V> column);

  <V> UpdateWrite<E> setDefault(Selectable<V> column);

  UpdateWrite<E> set(RowColumns columns, SelectSource<?> query);
}
