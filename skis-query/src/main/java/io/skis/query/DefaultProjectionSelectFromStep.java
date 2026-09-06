package io.skis.query;

import java.util.Objects;

/** Immutable FROM stage returned after selecting a generated result-row shape. */
final class DefaultProjectionSelectFromStep<R> implements ProjectionSelectFromStep<R> {

  private final DefaultQueryOperations operations;
  private final SelectedResult<?, R> selected;

  DefaultProjectionSelectFromStep(
      DefaultQueryOperations operations, ProjectionSelection<R> selection) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.selected = SelectedResult.projection(Objects.requireNonNull(selection, "selection"));
  }

  @Override
  public <F> SelectQuery<F, R> from(QueryTable<F> root) {
    return operations.selectFrom(selected, Objects.requireNonNull(root, "root"));
  }
}
