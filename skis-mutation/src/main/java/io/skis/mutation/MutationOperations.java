package io.skis.mutation;

import io.skis.core.ExecutionOptions;
import io.skis.query.QueryTable;

/** Entity mutation facet shared by the injected executor and explicit sessions. */
public interface MutationOperations {

  /** Inserts one writable entity and requires exactly one affected row. */
  <E> int insert(QueryTable<E> table, E value);

  /** Inserts one entity with immutable statement overrides. */
  <E> int insert(QueryTable<E> table, E value, ExecutionOptions executionOptions);

  /** Inserts one entity and returns its confirmed primary key through the typed entity. */
  <E> InsertResult<E> insertResult(QueryTable<E> table, E value);

  /** Inserts and returns the key-bearing entity with immutable statement overrides. */
  <E> InsertResult<E> insertResult(QueryTable<E> table, E value, ExecutionOptions executionOptions);

  /** Updates one entity by its generated primary-key and optimistic-version metadata. */
  <E> int updateById(QueryTable<E> table, E value);

  /** Updates one entity with immutable statement overrides. */
  <E> int updateById(QueryTable<E> table, E value, ExecutionOptions executionOptions);

  /** Deletes one entity by its generated single-column primary key. */
  <E> int deleteById(QueryTable<E> table, Object id);

  /** Deletes one entity with immutable statement overrides. */
  <E> int deleteById(QueryTable<E> table, Object id, ExecutionOptions executionOptions);
}
