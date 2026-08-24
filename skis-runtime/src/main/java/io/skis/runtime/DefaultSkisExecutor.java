package io.skis.runtime;

import io.skis.metadata.EntityMeta;
import io.skis.query.EntitySelectQuery;
import io.skis.query.QueryOperations;
import io.skis.query.QueryTable;
import java.util.Objects;
import java.util.Optional;

/** Default immutable executor assembled by {@link SkisExecutorFactory}. */
final class DefaultSkisExecutor implements SkisExecutor {

  private final QueryOperations queries;

  DefaultSkisExecutor(QueryOperations queries) {
    this.queries = Objects.requireNonNull(queries, "queries");
  }

  @Override
  public <E> Optional<E> findById(EntityMeta<E> entity, Object id) {
    return queries.findById(entity, id);
  }

  @Override
  public <E> EntitySelectQuery<E> selectFrom(QueryTable<E> table) {
    return queries.selectFrom(table);
  }
}
