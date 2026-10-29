package io.skis.mutation;

import io.skis.core.ExecutionOptions;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;

/** A completed single-row write shape; each write performs an independent operation. */
public interface SingleWrite<E> {

  int write();

  int write(ExecutionOptions options);

  ReturningWrite<E> returning();

  <R> ReturningWrite<R> returning(ProjectionSelection<R> projection);

  <R> ReturningWrite<R> returning(ProjectionType<R> projection);
}
