package io.skis.mutation.internal;

import io.skis.core.MutationException;
import io.skis.core.SkisConfigurationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.SkisStatementKind;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.StatementPurpose;
import io.skis.dialect.SqlRenderException;
import io.skis.dialect.spi.Dialect;
import io.skis.dialect.spi.DialectFeature;
import io.skis.dialect.spi.RenderedSql;
import io.skis.dialect.support.SqlCompiler;
import io.skis.dialect.support.SqlTemplate;
import io.skis.jdbc.internal.CompiledMutationPlan;
import io.skis.jdbc.internal.JdbcFingerprint;
import io.skis.mapping.spi.EntityMutationBinders;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.ParameterBinder;
import io.skis.mapping.spi.ParameterBindingObserver;
import io.skis.mapping.spi.PropertyRuntime;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.GeneratedModelAbi;
import io.skis.metadata.IdGeneration;
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
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Compiles reflection-free single-entity mutation Fast Paths. */
final class MutationPlanCompiler {

  private final Dialect dialect;
  private final ExecutionListenerDispatcher listenerDispatcher;

  MutationPlanCompiler(Dialect dialect, ExecutionListenerDispatcher listenerDispatcher) {
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    this.listenerDispatcher = Objects.requireNonNull(listenerDispatcher, "listenerDispatcher");
  }

  <E> EntityMutationPlanSet<E> compile(EntityRuntimeModel<E> model) {
    Objects.requireNonNull(model, "model");
    EntityMeta<E> entity = model.entity();
    if (entity.readOnly()) {
      throw MutationException.of(
          SkisErrorCode.MUTATION_ENTITY_READ_ONLY,
          "read-only entity '" + entity.entityName() + "' cannot compile mutation plans");
    }
    EntityMutationBinders<E> binders =
        model
            .mutationBinders()
            .orElseThrow(
                () ->
                    SkisConfigurationException.of(
                        SkisErrorCode.MUTATION_BINDERS_MISSING,
                        "entity '"
                            + entity.entityName()
                            + "' has no generated mutation binders; regenerate it with ABI "
                            + GeneratedModelAbi.CURRENT));
    PropertyMeta<E, ?> id = requireSinglePrimaryKey(entity);
    boolean identity = entity.primaryKey().orElseThrow().generation() == IdGeneration.IDENTITY;
    if (identity
        && !dialect.capabilities().supports(DialectFeature.JDBC_GENERATED_KEYS)
        && !dialect.capabilities().supports(DialectFeature.MUTATION_RETURNING)) {
      throw SqlRenderException.of(
          SkisErrorCode.DIALECT_FEATURE_UNSUPPORTED,
          dialect.id(),
          "JDBC_GENERATED_KEYS",
          "IDENTITY requires JDBC generated keys or mutation RETURNING support");
    }
    RuntimeMutationTable<E> table = new RuntimeMutationTable<>(entity);
    CompiledMutationPlan<E> insert = compileInsert(entity, table, binders);
    CompiledMutationPlan<E> uncheckedUpdate =
        compileUpdate(entity, table, id, binders.updateByIdUnchecked(), false);
    CompiledMutationPlan<E> checkedUpdate =
        entity.version().isEmpty()
            ? uncheckedUpdate
            : compileUpdate(entity, table, id, binders.updateById(), true);
    CompiledMutationPlan<Object> delete = compileDelete(model, table, id);
    return new EntityMutationPlanSet<>(model, insert, checkedUpdate, uncheckedUpdate, delete);
  }

  private <E> @Nullable CompiledMutationPlan<E> compileInsert(
      EntityMeta<E> entity, RuntimeMutationTable<E> table, EntityMutationBinders<E> binders) {
    List<PropertyMeta<E, ?>> properties =
        entity.properties().stream()
            .filter(property -> property.column().insertable())
            .filter(
                property ->
                    entity.primaryKey().orElseThrow().generation() != IdGeneration.IDENTITY
                        || property != entity.primaryKey().orElseThrow().properties().getFirst())
            .toList();
    if (properties.isEmpty()
        && entity.primaryKey().orElseThrow().generation() != IdGeneration.IDENTITY) {
      return null;
    }
    long startedNanos = listenerDispatcher.enabled() ? System.nanoTime() : 0L;
    CompiledMutationPlan<E> plan;
    try {
      List<ColumnExpression<?, ?>> columns = new ArrayList<>(properties.size());
      List<SqlExpression<?>> values = new ArrayList<>(properties.size());
      List<ParameterSlot<?>> expectedParameters = new ArrayList<>(properties.size());
      for (int index = 0; index < properties.size(); index++) {
        addInsertProperty(table, properties.get(index), index, columns, values, expectedParameters);
      }
      RenderedSql rendered = render(new InsertStatement(table, columns, values));
      requireParameterShape(entity, "insert", rendered, expectedParameters);
      plan = new CompiledMutationPlan<>("insert", dialect.id(), rendered, binders.insert());
    } catch (RuntimeException | Error failure) {
      notifyPrewarm(
          ExecutionOperationKind.INSERT, SkisStatementKind.INSERT, startedNanos, null, failure);
      throw failure;
    }
    notifyPrewarm(
        ExecutionOperationKind.INSERT, SkisStatementKind.INSERT, startedNanos, plan, null);
    return plan;
  }

  private <E> @Nullable CompiledMutationPlan<E> compileUpdate(
      EntityMeta<E> entity,
      RuntimeMutationTable<E> table,
      PropertyMeta<E, ?> id,
      ParameterBinder<E> binder,
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
    // Each helper below appends exactly one slot, so the current list size is the next ordinal.
    for (PropertyMeta<E, ?> property : updateProperties) {
      assignments.add(
          parameterAssignment(table, property, expectedParameters.size(), expectedParameters));
    }
    entity
        .version()
        .ifPresent(version -> assignments.add(incrementAssignment(table, version.property())));
    if (assignments.isEmpty()) {
      return null;
    }
    long startedNanos = listenerDispatcher.enabled() ? System.nanoTime() : 0L;
    CompiledMutationPlan<E> plan;
    try {
      List<SqlPredicate> predicates = new ArrayList<>(2);
      predicates.add(equalityPredicate(table, id, expectedParameters.size(), expectedParameters));
      if (versionChecked) {
        PropertyMeta<E, ?> version = entity.version().orElseThrow().property();
        predicates.add(
            equalityPredicate(table, version, expectedParameters.size(), expectedParameters));
      }
      SqlPredicate where =
          predicates.size() == 1 ? predicates.getFirst() : LogicalPredicate.and(predicates);
      RenderedSql rendered = render(new UpdateStatement(table, assignments, where));
      requireParameterShape(entity, "updateById", rendered, expectedParameters);
      plan = new CompiledMutationPlan<>("updateById", dialect.id(), rendered, binder);
    } catch (RuntimeException | Error failure) {
      notifyPrewarm(
          ExecutionOperationKind.UPDATE_BY_ID,
          SkisStatementKind.UPDATE,
          startedNanos,
          null,
          failure);
      throw failure;
    }
    notifyPrewarm(
        ExecutionOperationKind.UPDATE_BY_ID, SkisStatementKind.UPDATE, startedNanos, plan, null);
    return plan;
  }

  private <E> CompiledMutationPlan<Object> compileDelete(
      EntityRuntimeModel<E> model, RuntimeMutationTable<E> table, PropertyMeta<E, ?> id) {
    long startedNanos = listenerDispatcher.enabled() ? System.nanoTime() : 0L;
    CompiledMutationPlan<Object> plan;
    try {
      List<ParameterSlot<?>> expectedParameters = new ArrayList<>(1);
      RenderedSql rendered =
          render(new DeleteStatement(table, equalityPredicate(table, id, 0, expectedParameters)));
      requireParameterShape(model.entity(), "deleteById", rendered, expectedParameters);
      PropertyRuntime<E, ?> idRuntime = model.property(id);
      plan =
          new CompiledMutationPlan<>(
              "deleteById",
              dialect.id(),
              rendered,
              new DeleteParameterBinder<>(idRuntime, id.javaType()));
    } catch (RuntimeException | Error failure) {
      notifyPrewarm(
          ExecutionOperationKind.DELETE_BY_ID,
          SkisStatementKind.DELETE,
          startedNanos,
          null,
          failure);
      throw failure;
    }
    notifyPrewarm(
        ExecutionOperationKind.DELETE_BY_ID, SkisStatementKind.DELETE, startedNanos, plan, null);
    return plan;
  }

  private record DeleteParameterBinder<E>(PropertyRuntime<E, ?> runtime, Class<?> javaType)
      implements ParameterBinder<Object> {
    @Override
    public int bind(
        PreparedStatement statement, int firstIndex, Object value, JdbcWriteContext context)
        throws SQLException {
      runtime.bind(statement, firstIndex, value, context);
      return firstIndex + 1;
    }

    @Override
    public int bindObserved(
        PreparedStatement statement,
        int firstIndex,
        Object value,
        JdbcWriteContext context,
        ParameterBindingObserver observer)
        throws SQLException {
      runtime.bind(statement, firstIndex, value, context);
      if (observer.needsMore()) {
        observer.bound(firstIndex, javaType, value);
      }
      return firstIndex + 1;
    }
  }

  private void notifyPrewarm(
      ExecutionOperationKind operationKind,
      SkisStatementKind statementKind,
      long startedNanos,
      @Nullable CompiledMutationPlan<?> plan,
      @Nullable Throwable failure) {
    if (!listenerDispatcher.enabled()) {
      return;
    }
    long durationNanos = Math.max(0L, System.nanoTime() - startedNanos);
    String sql = plan == null ? null : plan.sql();
    listenerDispatcher.prewarmCompilationCompleted(
        operationKind,
        StatementPurpose.PRIMARY,
        statementKind,
        dialect.id(),
        durationNanos,
        sql == null ? null : JdbcFingerprint.of(sql),
        sql,
        failure);
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
    ParameterSlot<V> parameter = new ParameterSlot<>(parameterOrdinal, property.javaType(), false);
    expectedParameters.add(parameter);
    return table.expression(property).eq(parameter);
  }

  private RenderedSql render(StatementAst statement) {
    SqlTemplate template = SqlCompiler.compile(statement, dialect);
    return new RenderedSql(template.sql(), template.parameterLayout().slots());
  }

  private static <E> PropertyMeta<E, ?> requireSinglePrimaryKey(EntityMeta<E> entity) {
    List<PropertyMeta<E, ?>> keys =
        entity
            .primaryKey()
            .orElseThrow(
                () ->
                    MutationException.of(
                        SkisErrorCode.MUTATION_PRIMARY_KEY_REQUIRED,
                        "entity '" + entity.entityName() + "' has no primary key"))
            .properties();
    if (keys.size() != 1) {
      throw MutationException.of(
          SkisErrorCode.MUTATION_FAST_PATH_PRIMARY_KEY,
          "mutation Fast Paths require exactly one primary-key property for entity '"
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
      throw MutationException.of(
          SkisErrorCode.MUTATION_PARAMETER_SHAPE,
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
