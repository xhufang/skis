package io.skis.query;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable FROM stage shared by every executable result shape. */
final class DefaultSelectFromStep<R extends @Nullable Object>
    implements SelectFromStep<SelectQuery<R>> {

  private final DefaultQueryOperations operations;
  private final SelectedResult<R> selected;

  DefaultSelectFromStep(DefaultQueryOperations operations, SelectedResult<R> selected) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.selected = Objects.requireNonNull(selected, "selected");
  }

  @Override
  public SelectQuery<R> from(QueryTable<?> table) {
    return operations.selectFrom(selected, Objects.requireNonNull(table, "table"));
  }

  @Override
  public SelectQuery<R> from(DerivedRelation relation) {
    return operations.selectFrom(selected, Objects.requireNonNull(relation, "relation"));
  }
}
