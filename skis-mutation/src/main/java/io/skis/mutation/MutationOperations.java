package io.skis.mutation;

import io.skis.core.ExecutionOptions;
import io.skis.metadata.EntityMeta;
import java.util.Objects;

/** Entity mutation facet shared by the injected executor and explicit sessions. */
public interface MutationOperations {

  /** Inserts one writable entity and requires exactly one affected row. */
  <E> int insert(EntityMeta<E> entity, E value);

  /** Inserts one entity with immutable statement overrides. */
  default <E> int insert(EntityMeta<E> entity, E value, ExecutionOptions executionOptions) {
    requireEmptyOptions(executionOptions);
    return insert(entity, value);
  }

  /** Updates one entity by its generated primary-key and optimistic-version metadata. */
  <E> int updateById(EntityMeta<E> entity, E value);

  /** Updates one entity with immutable statement overrides. */
  default <E> int updateById(EntityMeta<E> entity, E value, ExecutionOptions executionOptions) {
    requireEmptyOptions(executionOptions);
    return updateById(entity, value);
  }

  /** Deletes one entity by its generated single-column primary key. */
  <E> int deleteById(EntityMeta<E> entity, Object id);

  /** Deletes one entity with immutable statement overrides. */
  default <E> int deleteById(EntityMeta<E> entity, Object id, ExecutionOptions executionOptions) {
    requireEmptyOptions(executionOptions);
    return deleteById(entity, id);
  }

  private static void requireEmptyOptions(ExecutionOptions executionOptions) {
    ExecutionOptions options = Objects.requireNonNull(executionOptions, "executionOptions");
    if (!options.isEmpty()) {
      throw new UnsupportedOperationException(
          "this MutationOperations implementation does not support execution options");
    }
  }
}
