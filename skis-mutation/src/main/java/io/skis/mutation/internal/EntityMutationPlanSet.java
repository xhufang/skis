package io.skis.mutation.internal;

import io.skis.core.MutationException;
import io.skis.core.SkisConfigurationException;
import io.skis.core.SkisErrorCode;
import io.skis.jdbc.internal.CompiledMutationPlan;
import io.skis.mapping.spi.EntityIdAccess;
import io.skis.mapping.spi.EntityMutationBinders;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.InsertArguments;
import io.skis.mapping.spi.JdbcTypeCodec;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.IdGeneration;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlExpression;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable precompiled mutation Fast Paths for one generated entity. */
final class EntityMutationPlanSet<E> {

  private final EntityRuntimeModel<E> model;
  private final Object structuredIdentity = new Object();
  private final EntityMutationBinders<E> binders;
  private final @Nullable CompiledMutationPlan<E> insert;
  private final @Nullable CompiledMutationPlan<E> checkedUpdate;
  private final @Nullable CompiledMutationPlan<E> uncheckedUpdate;
  private final CompiledMutationPlan<Object> delete;
  private final @Nullable CompiledMutationPlan<InsertArguments<E>> generatedInsert;
  private final boolean recordEntity;
  private final PropertyMeta<E, ?> idProperty;
  private final IdGeneration generation;
  private final JdbcTypeCodec<?> idCodec;
  private final RuntimeMutationTable<E> insertTable;
  private final InsertStatement insertStatement;

  EntityMutationPlanSet(
      EntityRuntimeModel<E> model,
      @Nullable CompiledMutationPlan<E> insert,
      @Nullable CompiledMutationPlan<E> checkedUpdate,
      @Nullable CompiledMutationPlan<E> uncheckedUpdate,
      CompiledMutationPlan<Object> delete) {
    this.model = Objects.requireNonNull(model, "model");
    this.binders =
        model
            .mutationBinders()
            .orElseThrow(
                () ->
                    SkisConfigurationException.of(
                        SkisErrorCode.MUTATION_BINDERS_MISSING,
                        "entity '"
                            + model.entity().entityName()
                            + "' has no generated mutation binders"));
    this.insert = insert;
    this.checkedUpdate = checkedUpdate;
    this.uncheckedUpdate = uncheckedUpdate;
    this.delete = Objects.requireNonNull(delete, "delete");
    this.recordEntity = model.entity().javaType().isRecord();
    var primaryKey = model.entity().primaryKey().orElseThrow();
    this.idProperty = primaryKey.properties().getFirst();
    this.generation = primaryKey.generation();
    this.idCodec = model.property(idProperty).codec();
    this.insertTable = new RuntimeMutationTable<>(model.entity());
    List<ColumnExpression<?, ?>> columns = new ArrayList<>();
    List<SqlExpression<?>> values = new ArrayList<>();
    for (var property : model.entity().properties()) {
      if (property.column().insertable()
          && !(generation == IdGeneration.IDENTITY && property == idProperty)) {
        columns.add(insertTable.expression(property));
        values.add(
            new ParameterSlot<>(values.size(), property.javaType(), property.column().nullable()));
      }
    }
    this.insertStatement = new InsertStatement(insertTable, columns, values);
    this.generatedInsert =
        insert == null || generation == IdGeneration.ASSIGNED
            ? null
            : new CompiledMutationPlan<>(
                insert.operation(),
                insert.dialectId(),
                insert.renderedSql(),
                binders.generatedInsert());
    boolean versioned = model.entity().version().isPresent();
    if (versioned == (binders.versionReader() == null)) {
      throw SkisConfigurationException.of(
          SkisErrorCode.MUTATION_BINDERS_MISSING,
          "generated version reader does not match metadata for entity '"
              + model.entity().entityName()
              + "'");
    }
  }

  Object structuredIdentity() {
    return structuredIdentity;
  }

  EntityMeta<E> entity() {
    return model.entity();
  }

  InsertStatement insertStatement() {
    return insertStatement;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  ColumnExpression<?, ?> returningColumn(PropertyMeta<?, ?> property) {
    return insertTable.expression((PropertyMeta) property);
  }

  CompiledMutationPlan<E> insert() {
    if (insert == null) {
      throw MutationException.of(
          SkisErrorCode.MUTATION_NO_INSERTABLE_PROPERTIES,
          "entity '" + entity().entityName() + "' has no insertable properties");
    }
    return insert;
  }

  CompiledMutationPlan<Object> delete() {
    return delete;
  }

  CompiledMutationPlan<InsertArguments<E>> generatedInsert() {
    insert(); // Preserve the existing no-insertable-properties failure.
    return Objects.requireNonNull(generatedInsert);
  }

  EntityIdAccess<E> idAccess() {
    return binders.idAccess();
  }

  PropertyMeta<E, ?> idProperty() {
    return idProperty;
  }

  IdGeneration generation() {
    return generation;
  }

  boolean recordEntity() {
    return recordEntity;
  }

  JdbcTypeCodec<?> idCodec() {
    return idCodec;
  }

  UpdateExecution<E> update(E value) {
    Object expectedVersion = readExpectedVersion(value);
    CompiledMutationPlan<E> selected = expectedVersion == null ? uncheckedUpdate : checkedUpdate;
    if (selected == null) {
      throw MutationException.of(
          SkisErrorCode.MUTATION_NO_UPDATABLE_PROPERTIES,
          "entity '" + entity().entityName() + "' has no updatable properties");
    }
    return new UpdateExecution<>(selected, expectedVersion != null);
  }

  @Nullable Object readExpectedVersion(E value) {
    if (binders.versionReader() == null) {
      return null;
    }
    Object expected;
    try {
      expected = binders.versionReader().read(value);
    } catch (SQLException failure) {
      throw MutationException.of(
          SkisErrorCode.MUTATION_VERSION_READ_FAILED,
          "cannot read optimistic version for entity '" + entity().entityName() + "'",
          failure);
    }
    if (expected == null) {
      return null;
    }
    PropertyMeta<E, ?> version = entity().version().orElseThrow().property();
    if (!version.javaType().isInstance(expected)) {
      throw MutationException.of(
          SkisErrorCode.MUTATION_VERSION_TYPE_MISMATCH,
          "optimistic version for entity '"
              + entity().entityName()
              + "' requires "
              + version.javaType().getTypeName());
    }
    return expected;
  }

  record UpdateExecution<E>(CompiledMutationPlan<E> plan, boolean versionChecked) {}
}
