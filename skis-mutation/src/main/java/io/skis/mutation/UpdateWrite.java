package io.skis.mutation;

import io.skis.core.ExecutionOptions;
import io.skis.query.DerivedRelation;
import io.skis.query.JoinOnStep;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;

/** Immutable conditional UPDATE; rows are chosen by one database operation. */
public interface UpdateWrite<E> extends ReturningSelection<E>, UpdateSet<E> {

  UpdateWrite<E> where(QueryCondition condition);

  UpdateWrite<E> and(QueryCondition condition);

  UpdateWrite<E> or(QueryCondition condition);

  UpdateWrite<E> allowAllRows();

  int write();

  int write(ExecutionOptions options);

  ReturningWrite<E> returning();

  <R> ReturningWrite<R> returning(ProjectionSelection<R> projection);

  <R> ReturningWrite<R> returning(ProjectionType<R> projection);

  UpdateWrite<E> from(QueryTable<?> source);

  JoinOnStep<UpdateWrite<E>> join(QueryTable<?> source);

  JoinOnStep<UpdateWrite<E>> innerJoin(QueryTable<?> source);

  JoinOnStep<UpdateWrite<E>> leftJoin(QueryTable<?> source);

  JoinOnStep<UpdateWrite<E>> rightJoin(QueryTable<?> source);

  JoinOnStep<UpdateWrite<E>> fullJoin(QueryTable<?> source);

  UpdateWrite<E> crossJoin(QueryTable<?> source);

  UpdateWrite<E> from(DerivedRelation source);

  JoinOnStep<UpdateWrite<E>> join(DerivedRelation source);

  JoinOnStep<UpdateWrite<E>> innerJoin(DerivedRelation source);

  JoinOnStep<UpdateWrite<E>> leftJoin(DerivedRelation source);

  JoinOnStep<UpdateWrite<E>> rightJoin(DerivedRelation source);

  JoinOnStep<UpdateWrite<E>> fullJoin(DerivedRelation source);

  UpdateWrite<E> crossJoin(DerivedRelation source);
}
