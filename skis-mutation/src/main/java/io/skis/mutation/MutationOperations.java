package io.skis.mutation;

import io.skis.metadata.EntityMeta;

/** Entity mutation facet shared by the injected executor and explicit sessions. */
public interface MutationOperations {

  /** Inserts one writable entity and requires exactly one affected row. */
  <E> int insert(EntityMeta<E> entity, E value);

  /** Updates one entity by its generated primary-key and optimistic-version metadata. */
  <E> int updateById(EntityMeta<E> entity, E value);

  /** Deletes one entity by its generated single-column primary key. */
  <E> int deleteById(EntityMeta<E> entity, Object id);
}
