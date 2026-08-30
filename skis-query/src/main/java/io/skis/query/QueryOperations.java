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

  /** Starts an immutable full-entity single-table query. */
  <E> EntitySelectQuery<E> selectFrom(QueryTable<E> table);

  /**
   * Starts a non-null scalar projection without constructing an intermediate tuple.
   *
   * <p>Nullable columns must use an APT-generated projection that maps each row to a non-null user
   * result so {@code fetchOne()} can distinguish SQL {@code NULL} from no row.
   */
  <E, V> SelectFromStep<E, V> select(QueryColumn<E, V> column);

  /** Selects one registered user projection from the supplied typed table expression. */
  <E, R> ProjectedSelectQuery<E, R> selectProjection(QueryTable<E> table, Class<R> projectionType);
}
