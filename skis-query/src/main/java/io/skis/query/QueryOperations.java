package io.skis.query;

import io.skis.metadata.EntityMeta;
import java.util.Optional;

/** Query facet shared by the injected SKIS executor and future explicit sessions. */
public interface QueryOperations {

  /** Executes the prewarmed, single-primary-key read Fast Path. */
  <E> Optional<E> findById(EntityMeta<E> entity, Object id);

  /** Starts an immutable full-entity single-table query. */
  <E> EntitySelectQuery<E> selectFrom(QueryTable<E> table);
}
