package io.skis.mutation;

import io.skis.core.ExecutionOptions;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryCondition;
import java.util.List;

/** Immutable conditional DELETE; explicit permission is required to omit a WHERE condition. */
public interface DeleteWrite<E> {

  DeleteWrite<E> where(QueryCondition condition);

  DeleteWrite<E> and(QueryCondition condition);

  DeleteWrite<E> or(QueryCondition condition);

  DeleteWrite<E> allowAllRows();

  int write();

  int write(ExecutionOptions options);

  ReturningWrite<List<E>> returning();

  <R> ReturningWrite<List<R>> returning(ProjectionSelection<R> projection);

  <R> ReturningWrite<List<R>> returning(ProjectionType<R> projection);
}
