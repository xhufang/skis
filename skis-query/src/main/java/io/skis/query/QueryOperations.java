package io.skis.query;

import io.skis.core.ExecutionOptions;
import io.skis.metadata.EntityMeta;
import java.util.Objects;
import java.util.Optional;

/** Query facet shared by the injected SKIS executor and future explicit sessions. */
public interface QueryOperations {

  /** Executes the prewarmed, single-primary-key read Fast Path. */
  <E> Optional<E> findById(EntityMeta<E> entity, Object id);

  /**
   * Executes the prewarmed primary-key read with immutable statement overrides.
   *
   * <p>The default preserves binary compatibility for custom implementations. Built-in SKIS
   * operations override this method; a custom implementation must do the same before accepting
   * non-empty options.
   */
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
  <E> SelectQuery<E, E> selectFrom(QueryTable<E> table);

  /** Selects one complete entity table before choosing an independent root. */
  <R> SelectFromStep<R> select(QueryTable<R> table);

  /** Selects an entity that may be absent on a null-extended outer-join side. */
  <R> NullableSelectFromStep<R> selectNullable(QueryTable<R> table);

  /** Starts a non-null scalar selection without constructing an intermediate tuple. */
  <V> SelectFromStep<V> select(NonNullSelectable<V> selectable);

  /** Starts a nullable scalar selection whose row-presence contract is {@link SingleRow}. */
  <V> NullableSelectFromStep<V> select(Selectable<V> selectable);

  /** Explicitly allows a declared non-null expression to become nullable in query context. */
  <V> NullableSelectFromStep<V> selectNullable(NonNullSelectable<V> selectable);

  /** Selects one APT-generated result-row shape before choosing an independent of root. */
  <R> SelectFromStep<R> select(ProjectionSelection<R> projection);
}
