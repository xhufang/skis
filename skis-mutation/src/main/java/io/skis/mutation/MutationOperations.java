package io.skis.mutation;

import io.skis.core.ExecutionOptions;
import io.skis.query.QueryTable;

/** Entity mutation facet shared by the injected executor and explicit sessions. */
public interface MutationOperations {

  /** Builds an insertion; only its terminal write accesses JDBC or generates a key. */
  <E> InsertInto<E> insertInto(QueryTable<E> table);

  /** Builds an independent SQL MERGE statement. */
  <E> MergeInto<E> mergeInto(QueryTable<E> table);

  /** Starts a conditional update; at least one assignment is required. */
  <E> UpdateSet<E> update(QueryTable<E> table);

  /** Starts a conditional physical delete. */
  <E> DeleteWrite<E> deleteFrom(QueryTable<E> table);

  /** Updates one entity by its generated primary-key and optimistic-version metadata. */
  <E> int updateById(QueryTable<E> table, E value);

  /** Updates one entity with immutable statement overrides. */
  <E> int updateById(QueryTable<E> table, E value, ExecutionOptions executionOptions);

  /** Deletes one entity by its generated single-column primary key. */
  <E> int deleteById(QueryTable<E> table, Object id);

  /** Deletes one entity with immutable statement overrides. */
  <E> int deleteById(QueryTable<E> table, Object id, ExecutionOptions executionOptions);
}
