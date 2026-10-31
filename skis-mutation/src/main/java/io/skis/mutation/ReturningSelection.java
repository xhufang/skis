package io.skis.mutation;

import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.Row2;
import io.skis.query.Row3;
import io.skis.query.Selectable;
import io.skis.query.internal.QueryDsl;
import org.jspecify.annotations.Nullable;

/** Explicit row outputs shared by all completed write shapes. */
public interface ReturningSelection<E> {

  ReturningWrite<E> returning();

  <R> ReturningWrite<R> returning(ProjectionSelection<R> projection);

  <R> ReturningWrite<R> returning(ProjectionType<R> projection);

  <V> ReturningWrite<V> returning(NonNullSelectable<V> selection);

  <V> ReturningWrite<@Nullable V> returning(Selectable<V> selection);

  default <A, B> ReturningWrite<Row2<@Nullable A, @Nullable B>> returning(
      Selectable<A> first, Selectable<B> second) {
    return returning(QueryDsl.<@Nullable A, @Nullable B>row2(first, second, 3));
  }

  default <A, B> ReturningWrite<Row2<A, @Nullable B>> returning(
      NonNullSelectable<A> first, Selectable<B> second) {
    return returning(QueryDsl.<A, @Nullable B>row2(first, second, 2));
  }

  default <A, B> ReturningWrite<Row2<@Nullable A, B>> returning(
      Selectable<A> first, NonNullSelectable<B> second) {
    return returning(QueryDsl.<@Nullable A, B>row2(first, second, 1));
  }

  default <A, B> ReturningWrite<Row2<A, B>> returning(
      NonNullSelectable<A> first, NonNullSelectable<B> second) {
    return returning(QueryDsl.row2(first, second, 0));
  }

  default <A, B, C> ReturningWrite<Row3<@Nullable A, @Nullable B, @Nullable C>> returning(
      Selectable<A> first, Selectable<B> second, Selectable<C> third) {
    return returning(QueryDsl.<@Nullable A, @Nullable B, @Nullable C>row3(first, second, third, 7));
  }

  default <A, B, C> ReturningWrite<Row3<A, @Nullable B, @Nullable C>> returning(
      NonNullSelectable<A> first, Selectable<B> second, Selectable<C> third) {
    return returning(QueryDsl.<A, @Nullable B, @Nullable C>row3(first, second, third, 6));
  }

  default <A, B, C> ReturningWrite<Row3<@Nullable A, B, @Nullable C>> returning(
      Selectable<A> first, NonNullSelectable<B> second, Selectable<C> third) {
    return returning(QueryDsl.<@Nullable A, B, @Nullable C>row3(first, second, third, 5));
  }

  default <A, B, C> ReturningWrite<Row3<A, B, @Nullable C>> returning(
      NonNullSelectable<A> first, NonNullSelectable<B> second, Selectable<C> third) {
    return returning(QueryDsl.<A, B, @Nullable C>row3(first, second, third, 4));
  }

  default <A, B, C> ReturningWrite<Row3<@Nullable A, @Nullable B, C>> returning(
      Selectable<A> first, Selectable<B> second, NonNullSelectable<C> third) {
    return returning(QueryDsl.<@Nullable A, @Nullable B, C>row3(first, second, third, 3));
  }

  default <A, B, C> ReturningWrite<Row3<A, @Nullable B, C>> returning(
      NonNullSelectable<A> first, Selectable<B> second, NonNullSelectable<C> third) {
    return returning(QueryDsl.<A, @Nullable B, C>row3(first, second, third, 2));
  }

  default <A, B, C> ReturningWrite<Row3<@Nullable A, B, C>> returning(
      Selectable<A> first, NonNullSelectable<B> second, NonNullSelectable<C> third) {
    return returning(QueryDsl.<@Nullable A, B, C>row3(first, second, third, 1));
  }

  default <A, B, C> ReturningWrite<Row3<A, B, C>> returning(
      NonNullSelectable<A> first, NonNullSelectable<B> second, NonNullSelectable<C> third) {
    return returning(QueryDsl.row3(first, second, third, 0));
  }
}
