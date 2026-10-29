package io.skis.mutation;

import io.skis.core.ExecutionOptions;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import java.util.List;

public interface MergeWrite<E> {

  int write();

  int write(ExecutionOptions options);

  ReturningWrite<List<E>> returning();

  <R> ReturningWrite<List<R>> returning(ProjectionSelection<R> projection);

  <R> ReturningWrite<List<R>> returning(ProjectionType<R> projection);
}
