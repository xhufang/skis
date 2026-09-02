package io.skis.mutation;

import io.skis.jdbc.CompiledMutationPlan;
import io.skis.mapping.EntityMutationBinders;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable precompiled mutation Fast Paths for one generated entity. */
final class EntityMutationPlanSet<E> {

  private final EntityRuntimeModel<E> model;
  private final EntityMutationBinders<E> binders;
  private final @Nullable CompiledMutationPlan<E> insert;
  private final @Nullable CompiledMutationPlan<E> checkedUpdate;
  private final @Nullable CompiledMutationPlan<E> uncheckedUpdate;
  private final CompiledMutationPlan<Object> delete;

  EntityMutationPlanSet(
      EntityRuntimeModel<E> model,
      @Nullable CompiledMutationPlan<E> insert,
      @Nullable CompiledMutationPlan<E> checkedUpdate,
      @Nullable CompiledMutationPlan<E> uncheckedUpdate,
      CompiledMutationPlan<Object> delete) {
    this.model = Objects.requireNonNull(model, "model");
    this.binders = model.mutationBinders().orElseThrow();
    this.insert = insert;
    this.checkedUpdate = checkedUpdate;
    this.uncheckedUpdate = uncheckedUpdate;
    this.delete = Objects.requireNonNull(delete, "delete");
    boolean versioned = model.entity().version().isPresent();
    if (versioned == (binders.versionReader() == null)) {
      throw new MutationException(
          "generated version reader does not match metadata for entity '"
              + model.entity().entityName()
              + "'");
    }
  }

  EntityMeta<E> entity() {
    return model.entity();
  }

  CompiledMutationPlan<E> insert() {
    if (insert == null) {
      throw new MutationException(
          "entity '" + entity().entityName() + "' has no insertable properties");
    }
    return insert;
  }

  CompiledMutationPlan<Object> delete() {
    return delete;
  }

  UpdateExecution<E> update(E value) {
    Object expectedVersion = readExpectedVersion(value);
    CompiledMutationPlan<E> selected = expectedVersion == null ? uncheckedUpdate : checkedUpdate;
    if (selected == null) {
      throw new MutationException(
          "entity '" + entity().entityName() + "' has no updatable properties");
    }
    return new UpdateExecution<>(selected, expectedVersion != null);
  }

  private @Nullable Object readExpectedVersion(E value) {
    if (binders.versionReader() == null) {
      return null;
    }
    Object expected;
    try {
      expected = binders.versionReader().read(value);
    } catch (SQLException failure) {
      throw new MutationException(
          "cannot read optimistic version for entity '" + entity().entityName() + "'", failure);
    }
    if (expected == null) {
      return null;
    }
    PropertyMeta<E, ?> version = entity().version().orElseThrow().property();
    if (!version.javaType().isInstance(expected)) {
      throw new MutationException(
          "optimistic version for entity '"
              + entity().entityName()
              + "' requires "
              + version.javaType().getTypeName());
    }
    return expected;
  }

  record UpdateExecution<E>(CompiledMutationPlan<E> plan, boolean versionChecked) {}
}
