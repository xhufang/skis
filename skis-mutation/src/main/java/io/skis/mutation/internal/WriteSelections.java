package io.skis.mutation.internal;

import io.skis.mutation.ReturningSelection;
import io.skis.mutation.ReturningWrite;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.internal.MutationQuerySupport;
import org.jspecify.annotations.Nullable;

/** Common result selection without eager decoder or execution allocation. */
abstract class WriteSelections<E> implements ReturningSelection<E> {

  final DefaultMutationOperations operations;
  final QueryTable<E> table;

  WriteSelections(DefaultMutationOperations operations, QueryTable<E> table) {
    this.operations = operations;
    this.table = table;
  }

  abstract <R extends @Nullable Object> ReturningWrite<R> outputs(
      MutationQuerySupport.Result<R> result);

  @Override
  public final ReturningWrite<E> returning() {
    return outputs(MutationQuerySupport.result(table));
  }

  @Override
  public final <R> ReturningWrite<R> returning(ProjectionSelection<R> projection) {
    return outputs(MutationQuerySupport.result(table, projection));
  }

  @Override
  public final <R> ReturningWrite<R> returning(ProjectionType<R> projection) {
    return outputs(MutationQuerySupport.result(table, projection));
  }

  @Override
  public final <V> ReturningWrite<V> returning(NonNullSelectable<V> selection) {
    return outputs(MutationQuerySupport.result(table, selection));
  }

  @Override
  public final <V> ReturningWrite<@Nullable V> returning(Selectable<V> selection) {
    return this.<@Nullable V>outputs(MutationQuerySupport.result(table, selection));
  }
}
