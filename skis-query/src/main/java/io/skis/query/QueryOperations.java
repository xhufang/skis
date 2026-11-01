package io.skis.query;

import io.skis.core.ExecutionOptions;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Query facet shared by the injected SKIS executor and explicit sessions. */
public interface QueryOperations {

  /** Executes the prewarmed, single-primary-key read Fast Path. */
  <E> Optional<E> findById(QueryTable<E> table, Object id);

  /** Executes the read Fast Path with immutable statement overrides. */
  <E> Optional<E> findById(QueryTable<E> table, Object id, ExecutionOptions executionOptions);

  /** Starts an immutable full-entity query rooted at the supplied table. */
  <E> ExecutableSelectQuery<E> selectFrom(QueryTable<E> table);

  /** Selects one complete entity before choosing an independent root. */
  <E> SelectFromStep<ExecutableSelectQuery<E>> select(QueryTable<E> table);

  /** Selects an entity that may be absent on a null-extended outer-join side. */
  <E> SelectFromStep<ExecutableSelectQuery<@Nullable E>> selectNullable(QueryTable<E> table);

  /** Starts a declared non-null scalar selection. */
  <V> SelectFromStep<ExecutableSingleColumnSelect<V>> select(NonNullSelectable<V> selectable);

  /** Starts a conservatively nullable scalar selection. */
  <V> SelectFromStep<ExecutableSingleColumnSelect<@Nullable V>> select(Selectable<V> selectable);

  /** Explicitly permits a declared non-null expression to produce a nullable query result. */
  <V> SelectFromStep<ExecutableSingleColumnSelect<@Nullable V>> selectNullable(
      NonNullSelectable<V> selectable);

  /** Selects one APT-generated result-row shape before choosing an independent root. */
  <R> SelectFromStep<ExecutableSelectQuery<R>> select(ProjectionSelection<R> projection);

  /** Selects one APT-generated special result whose sources bind in the final query block. */
  <R> SelectFromStep<ExecutableSelectQuery<R>> select(ProjectionType<R> projection);

  /** Executes a resource-free, strongly typed terminal request. */
  <T extends @Nullable Object> T run(QueryRequest<T> request);

  <A, B> SelectFromStep<ExecutableSelectQuery<Row2<@Nullable A, @Nullable B>>> select(
      Selectable<A> first, Selectable<B> second);

  <A, B> SelectFromStep<ExecutableSelectQuery<Row2<A, @Nullable B>>> select(
      NonNullSelectable<A> first, Selectable<B> second);

  <A, B> SelectFromStep<ExecutableSelectQuery<Row2<@Nullable A, B>>> select(
      Selectable<A> first, NonNullSelectable<B> second);

  <A, B> SelectFromStep<ExecutableSelectQuery<Row2<A, B>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second);

  <A, B, C>
      SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, @Nullable B, @Nullable C>>> select(
          Selectable<A> first, Selectable<B> second, Selectable<C> third);

  <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, @Nullable B, @Nullable C>>> select(
      NonNullSelectable<A> first, Selectable<B> second, Selectable<C> third);

  <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, B, @Nullable C>>> select(
      Selectable<A> first, NonNullSelectable<B> second, Selectable<C> third);

  <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, B, @Nullable C>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second, Selectable<C> third);

  <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, @Nullable B, C>>> select(
      Selectable<A> first, Selectable<B> second, NonNullSelectable<C> third);

  <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, @Nullable B, C>>> select(
      NonNullSelectable<A> first, Selectable<B> second, NonNullSelectable<C> third);

  <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, B, C>>> select(
      Selectable<A> first, NonNullSelectable<B> second, NonNullSelectable<C> third);

  <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, B, C>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second, NonNullSelectable<C> third);
}
