package io.skis.mutation;

import io.skis.core.ExecutionOptions;
import io.skis.query.DerivedRelation;
import io.skis.query.JoinOnStep;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;

/** Immutable conditional DELETE; explicit permission is required to omit a WHERE condition. */
public interface DeleteWrite<E> extends ReturningSelection<E> {

  DeleteWrite<E> where(QueryCondition condition);

  DeleteWrite<E> and(QueryCondition condition);

  DeleteWrite<E> or(QueryCondition condition);

  DeleteWrite<E> allowAllRows();

  int write();

  int write(ExecutionOptions options);

  ReturningWrite<E> returning();

  <R> ReturningWrite<R> returning(ProjectionSelection<R> projection);

  <R> ReturningWrite<R> returning(ProjectionType<R> projection);

  DeleteWrite<E> using(QueryTable<?> source);

  JoinOnStep<DeleteWrite<E>> join(QueryTable<?> source);

  JoinOnStep<DeleteWrite<E>> innerJoin(QueryTable<?> source);

  JoinOnStep<DeleteWrite<E>> leftJoin(QueryTable<?> source);

  JoinOnStep<DeleteWrite<E>> rightJoin(QueryTable<?> source);

  JoinOnStep<DeleteWrite<E>> fullJoin(QueryTable<?> source);

  DeleteWrite<E> crossJoin(QueryTable<?> source);

  DeleteWrite<E> using(DerivedRelation source);

  JoinOnStep<DeleteWrite<E>> join(DerivedRelation source);

  JoinOnStep<DeleteWrite<E>> innerJoin(DerivedRelation source);

  JoinOnStep<DeleteWrite<E>> leftJoin(DerivedRelation source);

  JoinOnStep<DeleteWrite<E>> rightJoin(DerivedRelation source);

  JoinOnStep<DeleteWrite<E>> fullJoin(DerivedRelation source);

  DeleteWrite<E> crossJoin(DerivedRelation source);
}
