package io.skis.query;

import io.skis.core.ExecutionOptions;
import io.skis.metadata.EntityMeta;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Query facet shared by the injected SKIS executor and explicit sessions. */
public interface QueryOperations {

  /** Executes the prewarmed, single-primary-key read Fast Path. */
  <E> Optional<E> findById(EntityMeta<E> entity, Object id);

  default <E> Optional<E> findById(
      EntityMeta<E> entity, Object id, ExecutionOptions executionOptions) {
    ExecutionOptions options = Objects.requireNonNull(executionOptions, "executionOptions");
    if (!options.isEmpty()) {
      throw new UnsupportedOperationException(
          "this QueryOperations implementation does not support execution options");
    }
    return findById(entity, id);
  }

  /** Starts an immutable full-entity query rooted at the supplied table. */
  <E> SelectQuery<E> selectFrom(QueryTable<E> table);

  /** Selects one complete entity before choosing an independent root. */
  <E> SelectFromStep<SelectQuery<E>> select(QueryTable<E> table);

  /** Selects an entity that may be absent on a null-extended outer-join side. */
  <E> SelectFromStep<SelectQuery<@Nullable E>> selectNullable(QueryTable<E> table);

  /** Starts a declared non-null scalar selection. */
  <V> SelectFromStep<SelectQuery<V>> select(NonNullSelectable<V> selectable);

  /** Starts a conservatively nullable scalar selection. */
  <V> SelectFromStep<SelectQuery<@Nullable V>> select(Selectable<V> selectable);

  /** Explicitly permits a declared non-null expression to produce a nullable query result. */
  <V> SelectFromStep<SelectQuery<@Nullable V>> selectNullable(NonNullSelectable<V> selectable);

  /** Selects one APT-generated result-row shape before choosing an independent root. */
  <R> SelectFromStep<SelectQuery<R>> select(ProjectionSelection<R> projection);

  /** Adapts one reusable query structure; call {@link SelectQuery#bind} for its values. */
  <R extends @Nullable Object> SelectQuery<R> query(SelectDescription<R> description);
}
