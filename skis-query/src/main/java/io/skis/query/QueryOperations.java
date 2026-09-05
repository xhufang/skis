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
  <R> SelectFromStep<R, R> select(QueryTable<R> table);

  /** Selects an entity that may be absent on a null-extended outer-join side. */
  <R> NullableSelectFromStep<R, R> selectNullable(QueryTable<R> table);

  /** Starts a non-null scalar projection without constructing an intermediate tuple. */
  <E, V> SelectFromStep<E, V> select(NonNullQueryColumn<E, V> column);

  /** Starts a nullable scalar projection whose row-presence contract is {@link SingleRow}. */
  <E, V> NullableSelectFromStep<E, V> select(NullableQueryColumn<E, V> column);

  /** Explicitly allows a physically non-null column to become nullable through an outer join. */
  <E, V> NullableSelectFromStep<E, V> selectNullable(NonNullQueryColumn<E, V> column);

  /** Selects one APT-generated result-row shape before choosing an independent FROM root. */
  <R> ProjectionSelectFromStep<R> select(ProjectionSelection<R> projection);
}
