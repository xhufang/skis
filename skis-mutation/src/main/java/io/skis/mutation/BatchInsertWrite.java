package io.skis.mutation;

import io.skis.core.ExecutionOptions;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import java.util.List;

/** Ordered snapshot of collection inputs; unsupported nonempty batches fail before JDBC. */
public interface BatchInsertWrite<E> {

  int write();

  int write(ExecutionOptions options);

  ReturningWrite<List<E>> returning();

  <R> ReturningWrite<List<R>> returning(ProjectionSelection<R> projection);

  <R> ReturningWrite<List<R>> returning(ProjectionType<R> projection);
}
