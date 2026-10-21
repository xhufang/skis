package io.skis.query.internal;

import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.SkisStatementKind;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.StatementPurpose;
import io.skis.jdbc.internal.CompiledQueryPlan;
import io.skis.jdbc.internal.JdbcFingerprint;
import io.skis.mapping.spi.EntityRuntimeModel;
import io.skis.metadata.PropertyMeta;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlExpression;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.jspecify.annotations.Nullable;

/** Fixed entity Fast Path plan slots plus the compiler used by immutable query objects. */
final class EntityPlanSet<E> {

  private final EntityRuntimeModel<E> model;
  private final QueryPlanCompiler compiler;
  private final ExecutionListenerDispatcher listenerDispatcher;
  private final String dialectId;
  private final RuntimeQueryTable<E> canonicalTable;
  private final AtomicReference<@Nullable CompiledQueryPlan<E, Object>> selectAll =
      new AtomicReference<>();
  private final AtomicReferenceArray<@Nullable CompiledQueryPlan<E, Object>> equalities;
  private final @Nullable PropertyMeta<E, ?> findByIdProperty;
  private final @Nullable CompiledQueryPlan<E, Object> findByIdPlan;

  EntityPlanSet(EntityRuntimeModel<E> model, QueryPlanCompiler compiler) {
    this(model, compiler, ExecutionListenerDispatcher.NONE, compiler.dialectId());
  }

  EntityPlanSet(
      EntityRuntimeModel<E> model,
      QueryPlanCompiler compiler,
      ExecutionListenerDispatcher listenerDispatcher,
      String dialectId) {
    this.model = Objects.requireNonNull(model, "model");
    this.compiler = Objects.requireNonNull(compiler, "compiler");
    this.listenerDispatcher = Objects.requireNonNull(listenerDispatcher, "listenerDispatcher");
    this.dialectId = Objects.requireNonNull(dialectId, "dialectId");
    this.canonicalTable = new RuntimeQueryTable<>(model.entity());
    this.equalities = new AtomicReferenceArray<>(model.entity().properties().size());
    this.findByIdProperty = resolveFindByIdProperty();
    this.findByIdPlan =
        findByIdProperty == null ? null : prewarmEqualityPlan(findByIdProperty.ordinal());
  }

  EntityRuntimeModel<E> model() {
    return model;
  }

  QueryPlanCompiler compiler() {
    return compiler;
  }

  CompiledQueryPlan<E, Object> findByIdPlan() {
    CompiledQueryPlan<E, Object> plan = findByIdPlan;
    if (plan == null) {
      throw unsupportedFindById();
    }
    return plan;
  }

  PropertyMeta<E, ?> findByIdProperty() {
    PropertyMeta<E, ?> property = findByIdProperty;
    if (property == null) {
      throw unsupportedFindById();
    }
    return property;
  }

  CompiledQueryPlan<E, Object> selectPlanForStructure(
      QueryTable<E> table, CompiledQueryStructure structure) {
    return selectPlanForStructure(table, structure, null, StatementPurpose.PRIMARY);
  }

  CompiledQueryPlan<E, Object> selectPlanForStructure(
      QueryTable<E> table,
      CompiledQueryStructure structure,
      @Nullable OperationObservation observation,
      StatementPurpose purpose) {
    Objects.requireNonNull(structure, "structure");
    if (structure.where() == null) {
      return table.alias().isEmpty()
          ? cachedSelectAll(observation, purpose)
          : compiler.compileQuery(model, table, structure, observation, purpose);
    }
    PropertyMeta<E, ?> property = simpleEqualityProperty(table, structure);
    if (property == null || !supportsCachedEquality(structure, property)) {
      return compiler.compileQuery(model, table, structure, observation, purpose);
    }
    return table.alias().isEmpty()
        ? equalityPlan(property.ordinal(), observation, purpose)
        : compiler.compileQuery(model, table, structure, observation, purpose);
  }

  private CompiledQueryPlan<E, Object> cachedSelectAll(
      @Nullable OperationObservation observation, StatementPurpose purpose) {
    CompiledQueryPlan<E, Object> existing = selectAll.get();
    if (existing != null) {
      return existing;
    }
    long startedNanos = observation == null ? 0L : System.nanoTime();
    CompiledQueryPlan<E, Object> compiled;
    try {
      compiled = compiler.compile(model, canonicalTable, null);
    } catch (RuntimeException | Error failure) {
      notifyOnDemand(observation, purpose, startedNanos, null, failure);
      throw failure;
    }
    notifyOnDemand(observation, purpose, startedNanos, compiled, null);
    CompiledQueryPlan<E, Object> published = selectAll.compareAndExchange(null, compiled);
    return published == null ? compiled : published;
  }

  private boolean supportsCachedEquality(
      CompiledQueryStructure structure, PropertyMeta<E, ?> property) {
    if (structure.parameterSlots().size() != 1 || structure.parameterSources().size() != 1) {
      return false;
    }
    var slot = structure.parameterSlots().getFirst();
    Selectable<?> selectable = structure.parameterSources().getFirst();
    if (!(selectable instanceof QueryColumn<?, ?> source)) {
      return false;
    }
    return slot.nullability() == Nullability.NON_NULL
        && slot.javaType().equals(property.javaType())
        && slot.sqlType() == source.sqlType()
        && source.property() == property;
  }

  private @Nullable PropertyMeta<E, ?> simpleEqualityProperty(
      QueryTable<E> table, CompiledQueryStructure structure) {
    if (!(structure.where()
            instanceof
            ComparisonPredicate<?>(
                SqlExpression<?> left,
                ComparisonOperator operator,
                SqlExpression<?> right))
        || operator != ComparisonOperator.EQUAL
        || !(right instanceof ParameterSlot<?>)) {
      return null;
    }
    if (structure.parameterSources().size() != 1) {
      return null;
    }
    Selectable<?> source = structure.parameterSources().getFirst();
    if (!(source instanceof QueryColumn<?, ?> column)
        || column.table() != table
        || left != column.expression()) {
      return null;
    }
    @SuppressWarnings("unchecked")
    PropertyMeta<E, ?> property = (PropertyMeta<E, ?>) column.property();
    return property;
  }

  private CompiledQueryPlan<E, Object> equalityPlan(
      int ordinal, @Nullable OperationObservation observation, StatementPurpose purpose) {
    CompiledQueryPlan<E, Object> existing = equalities.get(ordinal);
    if (existing != null) {
      return existing;
    }
    PropertyMeta<E, ?> property = model.entity().properties().get(ordinal);
    long startedNanos = observation == null ? 0L : System.nanoTime();
    CompiledQueryPlan<E, Object> compiled;
    try {
      compiled = compiler.compile(model, canonicalTable, property);
    } catch (RuntimeException | Error failure) {
      notifyOnDemand(observation, purpose, startedNanos, null, failure);
      throw failure;
    }
    notifyOnDemand(observation, purpose, startedNanos, compiled, null);
    CompiledQueryPlan<E, Object> published = equalities.compareAndExchange(ordinal, null, compiled);
    return published == null ? compiled : published;
  }

  private CompiledQueryPlan<E, Object> prewarmEqualityPlan(int ordinal) {
    long startedNanos = listenerDispatcher.enabled() ? System.nanoTime() : 0L;
    CompiledQueryPlan<E, Object> plan;
    try {
      plan = equalityPlan(ordinal, null, StatementPurpose.PRIMARY);
    } catch (RuntimeException | Error failure) {
      if (listenerDispatcher.enabled()) {
        long durationNanos = Math.max(0L, System.nanoTime() - startedNanos);
        listenerDispatcher.prewarmCompilationCompleted(
            ExecutionOperationKind.FIND_BY_ID,
            StatementPurpose.PRIMARY,
            SkisStatementKind.SELECT,
            dialectId,
            durationNanos,
            null,
            null,
            failure);
      }
      throw failure;
    }
    if (listenerDispatcher.enabled()) {
      long durationNanos = Math.max(0L, System.nanoTime() - startedNanos);
      listenerDispatcher.prewarmCompilationCompleted(
          ExecutionOperationKind.FIND_BY_ID,
          StatementPurpose.PRIMARY,
          SkisStatementKind.SELECT,
          dialectId,
          durationNanos,
          JdbcFingerprint.of(plan.sql()),
          plan.sql(),
          null);
    }
    return plan;
  }

  private static void notifyOnDemand(
      @Nullable OperationObservation observation,
      StatementPurpose purpose,
      long startedNanos,
      @Nullable CompiledQueryPlan<?, ?> plan,
      @Nullable Throwable failure) {
    if (observation == null) {
      return;
    }
    long durationNanos = Math.max(0L, System.nanoTime() - startedNanos);
    String sql = plan == null ? null : plan.sql();
    observation.compilationCompleted(
        purpose,
        SkisStatementKind.SELECT,
        durationNanos,
        sql == null ? null : JdbcFingerprint.of(sql),
        sql,
        failure);
  }

  private @Nullable PropertyMeta<E, ?> resolveFindByIdProperty() {
    return model
        .entity()
        .primaryKey()
        .filter(primaryKey -> primaryKey.properties().size() == 1)
        .map(primaryKey -> primaryKey.properties().getFirst())
        .orElse(null);
  }

  private QueryValidationException unsupportedFindById() {
    return QueryValidationException.of(
        SkisErrorCode.QUERY_METADATA_REQUIRED,
        "findById requires exactly one primary-key property for entity '"
            + model.entity().entityName()
            + "'");
  }
}
