package io.skis.mutation;

import io.skis.dialect.Dialect;
import io.skis.dialect.RenderedSql;
import io.skis.jdbc.CompiledMutationPlan;
import io.skis.mapping.EntityMutationBinders;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.PropertyRuntime;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Compiles reflection-free single-entity mutation Fast Paths. */
final class MutationPlanCompiler {

  private final Dialect dialect;

  MutationPlanCompiler(Dialect dialect) {
    this.dialect = Objects.requireNonNull(dialect, "dialect");
  }

  <E> EntityMutationPlanSet<E> compile(EntityRuntimeModel<E> model) {
    Objects.requireNonNull(model, "model");
    EntityMeta<E> entity = model.entity();
    if (entity.readOnly()) {
      throw new MutationException(
          "read-only entity '" + entity.entityName() + "' cannot compile mutation plans");
    }
    EntityMutationBinders<E> binders =
        model
            .mutationBinders()
            .orElseThrow(
                () ->
                    new MutationException(
                        "entity '"
                            + entity.entityName()
                            + "' has no generated mutation binders; regenerate it with ABI 3"));
    PropertyMeta<E, ?> id = requireSinglePrimaryKey(entity);
    RuntimeMutationTable<E> table = new RuntimeMutationTable<>(entity);
    @Nullable CompiledMutationPlan<E> insert = compileInsert(entity, table, binders);
    @Nullable CompiledMutationPlan<E> uncheckedUpdate =
        compileUpdate(entity, table, id, binders.updateByIdUnchecked(), false);
    @Nullable CompiledMutationPlan<E> checkedUpdate =
        entity.version().isEmpty()
            ? uncheckedUpdate
            : compileUpdate(entity, table, id, binders.updateById(), true);
    CompiledMutationPlan<Object> delete = compileDelete(model, table, id);
    return new EntityMutationPlanSet<>(model, insert, checkedUpdate, uncheckedUpdate, delete);
  }

  private <E> @Nullable CompiledMutationPlan<E> compileInsert(
      EntityMeta<E> entity, RuntimeMutationTable<E> table, EntityMutationBinders<E> binders) {
    List<PropertyMeta<E, ?>> properties =
        entity.properties().stream().filter(property -> property.column().insertable()).toList();
    if (properties.isEmpty()) {
      return null;
    }
    List<ColumnExpression<?, ?>> columns = new ArrayList<>(properties.size());
    List<SqlExpression<?>> values = new ArrayList<>(properties.size());
    List<ParameterSlot<?>> expectedParameters = new ArrayList<>(properties.size());
    for (int index = 0; index < properties.size(); index++) {
      addInsertProperty(
          table, properties.get(index), index, columns, values, expectedParameters);
    }
    RenderedSql rendered = dialect.renderer().render(new InsertStatement(table, columns, values));
    requireParameterShape(entity, "insert", rendered, expectedParameters);
    return new CompiledMutationPlan<>("insert", dialect.id(), rendered, binders.insert());
  }

  private <E> @Nullable CompiledMutationPlan<E> compileUpdate(
      EntityMeta<E> entity,
      RuntimeMutationTable<E> table,
      PropertyMeta<E, ?> id,
      io.skis.mapping.ParameterBinder<E> binder,
      boolean versionChecked) {
    List<PropertyMeta<E, ?>> updateProperties =
        entity.properties().stream()
            .filter(property -> property.column().updatable())
            .filter(property -> property != id)
            .filter(
                property ->
                    entity.version().map(version -> version.property() != property).orElse(true))
            .toList();
    List<UpdateAssignment<?>> assignments = new ArrayList<>();
    List<ParameterSlot<?>> expectedParameters = new ArrayList<>();
    int parameterOrdinal = 0;
    for (PropertyMeta<E, ?> property : updateProperties) {
      assignments.add(
          parameterAssignment(table, property, parameterOrdinal++, expectedParameters));
    }
    entity
        .version()
        .ifPresent(version -> assignments.add(incrementAssignment(table, version.property())));
    if (assignments.isEmpty()) {
      return null;
    }

    List<SqlPredicate> predicates = new ArrayList<>(2);
    predicates.add(equalityPredicate(table, id, parameterOrdinal++, expectedParameters));
    if (versionChecked) {
      PropertyMeta<E, ?> version = entity.version().orElseThrow().property();
      predicates.add(
          equalityPredicate(table, version, parameterOrdinal++, expectedParameters));
    }
    SqlPredicate where =
        predicates.size() == 1 ? predicates.getFirst() : LogicalPredicate.and(predicates);
    RenderedSql rendered =
        dialect.renderer().render(new UpdateStatement(table, assignments, where));
    requireParameterShape(entity, "updateById", rendered, expectedParameters);
    return new CompiledMutationPlan<>("updateById", dialect.id(), rendered, binder);
  }

  private <E> CompiledMutationPlan<Object> compileDelete(
      EntityRuntimeModel<E> model, RuntimeMutationTable<E> table, PropertyMeta<E, ?> id) {
    List<ParameterSlot<?>> expectedParameters = new ArrayList<>(1);
    RenderedSql rendered =
        dialect
            .renderer()
            .render(
                new DeleteStatement(
                    table, equalityPredicate(table, id, 0, expectedParameters)));
    requireParameterShape(model.entity(), "deleteById", rendered, expectedParameters);
    PropertyRuntime<E, ?> idRuntime = model.property(id);
    return new CompiledMutationPlan<>(
        "deleteById",
        dialect.id(),
        rendered,
        (statement, firstIndex, value, context) -> {
          idRuntime.bind(statement, firstIndex, value, context);
          return firstIndex + 1;
        });
  }

  private static <E, V> void addInsertProperty(
      RuntimeMutationTable<E> table,
      PropertyMeta<E, V> property,
      int parameterOrdinal,
      List<ColumnExpression<?, ?>> columns,
      List<SqlExpression<?>> values,
      List<ParameterSlot<?>> expectedParameters) {
    columns.add(table.expression(property));
    ParameterSlot<V> parameter =
        new ParameterSlot<>(parameterOrdinal, property.javaType(), property.column().nullable());
    values.add(parameter);
    expectedParameters.add(parameter);
  }

  private static <E, V> UpdateAssignment<V> parameterAssignment(
      RuntimeMutationTable<E> table,
      PropertyMeta<E, V> property,
      int parameterOrdinal,
      List<ParameterSlot<?>> expectedParameters) {
    ParameterSlot<V> parameter =
        new ParameterSlot<>(parameterOrdinal, property.javaType(), property.column().nullable());
    expectedParameters.add(parameter);
    return new UpdateAssignment<>(table.expression(property), parameter);
  }

  private static <E, V> UpdateAssignment<V> incrementAssignment(
      RuntimeMutationTable<E> table, PropertyMeta<E, V> property) {
    ColumnExpression<E, V> column = table.expression(property);
    return new UpdateAssignment<>(column, new IncrementExpression<>(column));
  }

  private static <E, V> ComparisonPredicate<V> equalityPredicate(
      RuntimeMutationTable<E> table,
      PropertyMeta<E, V> property,
      int parameterOrdinal,
      List<ParameterSlot<?>> expectedParameters) {
    ParameterSlot<V> parameter =
        new ParameterSlot<>(parameterOrdinal, property.javaType(), false);
    expectedParameters.add(parameter);
    return table.expression(property).eq(parameter);
  }

  private static <E> PropertyMeta<E, ?> requireSinglePrimaryKey(EntityMeta<E> entity) {
    List<PropertyMeta<E, ?>> keys =
        entity
            .primaryKey()
            .orElseThrow(
                () ->
                    new MutationException(
                        "entity '" + entity.entityName() + "' has no primary key"))
            .properties();
    if (keys.size() != 1) {
      throw new MutationException(
          "0.0.7 mutation Fast Paths require one primary-key property for entity '"
              + entity.entityName()
              + "'");
    }
    return keys.getFirst();
  }

  private static void requireParameterShape(
      EntityMeta<?> entity,
      String operation,
      RenderedSql rendered,
      List<ParameterSlot<?>> expected) {
    if (!rendered.parameters().equals(expected)) {
      throw new MutationException(
          "dialect rendered an unexpected "
              + operation
              + " parameter shape for entity '"
              + entity.entityName()
              + "' [expected="
              + expected
              + ", actual="
              + rendered.parameters()
              + "]");
    }
  }
}
