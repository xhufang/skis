package io.skis.mutation;

import io.skis.core.ExecutionOptions;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryCondition;
import java.util.List;

/** Immutable conditional UPDATE; rows are chosen by one database operation. */
public interface UpdateWrite<E> extends UpdateSet<E> {

  UpdateWrite<E> where(QueryCondition condition);

  UpdateWrite<E> and(QueryCondition condition);

  UpdateWrite<E> or(QueryCondition condition);

  UpdateWrite<E> allowAllRows();

  int write();

  int write(ExecutionOptions options);

  ReturningWrite<List<E>> returning();

  <R> ReturningWrite<List<R>> returning(ProjectionSelection<R> projection);

  <R> ReturningWrite<List<R>> returning(ProjectionType<R> projection);
}
