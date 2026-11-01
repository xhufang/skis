package io.skis.mutation;

import io.skis.core.ExecutionOptions;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;

public interface MergeWrite<E> extends ReturningSelection<E>, MergeActions<E> {

  int write();

  int write(ExecutionOptions options);

  ReturningWrite<E> returning();

  <R> ReturningWrite<R> returning(ProjectionSelection<R> projection);

  <R> ReturningWrite<R> returning(ProjectionType<R> projection);
}
