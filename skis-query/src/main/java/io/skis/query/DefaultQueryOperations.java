package io.skis.query;

import io.skis.core.ExecutionContext;
import io.skis.core.ExecutionOptions;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.jdbc.JdbcExecutor;
import io.skis.jdbc.JdbcPageResult;
import io.skis.jdbc.JdbcRow;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Default immutable query facade; package-private so users depend on the stable interface. */
final class DefaultQueryOperations implements QueryOperations {

  private final JdbcExecutor jdbcExecutor;
  private final QueryPlanCatalog planCatalog;

  DefaultQueryOperations(QueryPlanCatalog planCatalog, JdbcExecutor jdbcExecutor) {
    this.planCatalog = Objects.requireNonNull(planCatalog, "planCatalog");
    this.jdbcExecutor = Objects.requireNonNull(jdbcExecutor, "jdbcExecutor");
  }

  @Override
  public <E> Optional<E> findById(EntityMeta<E> entity, Object id) {
    return findById(entity, id, ExecutionContext.EMPTY);
  }

  @Override
  public <E> Optional<E> findById(
      EntityMeta<E> entity, Object id, ExecutionOptions executionOptions) {
    ExecutionContext executionContext =
        ExecutionContext.of(Objects.requireNonNull(executionOptions, "executionOptions"));
    return findById(entity, id, executionContext);
  }

  private <E> Optional<E> findById(
      EntityMeta<E> entity, Object id, ExecutionContext executionContext) {
    Objects.requireNonNull(entity, "entity");
    EntityPlanSet<E> plans = requirePlanSet(entity);
    PropertyMeta<E, ?> idProperty = plans.findByIdProperty();
    requireValueType(idProperty, id);
    return jdbcExecutor.fetchOne(plans.findByIdPlan(), id, executionContext);
  }

  @Override
  public <E> SelectQuery<E, E> selectFrom(QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    EntityPlanSet<E> plans = requirePlanSet(table.entity());
    return DefaultSelectQuery.create(this, plans, table, SelectedResult.entity(table, plans));
  }

  @Override
  public <R> SelectFromStep<R, R> select(QueryTable<R> table) {
    Objects.requireNonNull(table, "table");
    EntityPlanSet<R> plans = requirePlanSet(table.entity());
    return new DefaultSelectFromStep<>(this, SelectedResult.entity(table, plans));
  }

  @Override
  public <R> NullableSelectFromStep<R, R> selectNullable(QueryTable<R> table) {
    Objects.requireNonNull(table, "table");
    EntityPlanSet<R> plans = requirePlanSet(table.entity());
    return new DefaultNullableSelectFromStep<>(this, SelectedResult.nullableEntity(table, plans));
  }

  @Override
  public <E, V> SelectFromStep<E, V> select(NonNullQueryColumn<E, V> column) {
    Objects.requireNonNull(column, "column");
    QueryTable<E> selectedTable = column.table();
    EntityPlanSet<E> plans = requirePlanSet(selectedTable.entity());
    return new DefaultSelectFromStep<>(
        this, SelectedResult.requiredScalar(selectedTable, plans, column));
  }

  @Override
  public <E, V> NullableSelectFromStep<E, V> select(NullableQueryColumn<E, V> column) {
    Objects.requireNonNull(column, "column");
    QueryTable<E> selectedTable = column.table();
    EntityPlanSet<E> plans = requirePlanSet(selectedTable.entity());
    return new DefaultNullableSelectFromStep<>(
        this, SelectedResult.nullableScalar(selectedTable, plans, column));
  }

  @Override
  public <E, V> NullableSelectFromStep<E, V> selectNullable(NonNullQueryColumn<E, V> column) {
    Objects.requireNonNull(column, "column");
    QueryTable<E> selectedTable = column.table();
    EntityPlanSet<E> plans = requirePlanSet(selectedTable.entity());
    return new DefaultNullableSelectFromStep<>(
        this, SelectedResult.nullableScalar(selectedTable, plans, column));
  }

  @Override
  public <R> ProjectionSelectFromStep<R> select(ProjectionSelection<R> projection) {
    return new DefaultProjectionSelectFromStep<>(
        this, Objects.requireNonNull(projection, "projection"));
  }

  <F, S, R> DefaultSelectQuery<F, R> selectFrom(
      SelectedResult<S, R> selected, QueryTable<F> table) {
    Objects.requireNonNull(selected, "selected");
    EntityPlanSet<F> plans = requirePlanSet(table.entity());
    return DefaultSelectQuery.create(this, plans, table, selected);
  }

  <R> Optional<R> fetchOne(
      CompiledQueryPlan<R, Object> plan, Object argument, ExecutionContext executionContext) {
    return jdbcExecutor.fetchOne(plan, argument, executionContext);
  }

  <R> List<@Nullable R> fetchList(
      CompiledQueryPlan<R, Object> plan, Object argument, ExecutionContext executionContext) {
    return jdbcExecutor.fetchList(plan, argument, executionContext);
  }

  <R> Optional<R> fetchFirst(
      CompiledQueryPlan<R, Object> plan, Object argument, ExecutionContext executionContext) {
    return jdbcExecutor.fetchFirst(plan, argument, executionContext);
  }

  <R> JdbcRow<R> fetchNullableOne(
      CompiledQueryPlan<R, Object> plan, Object argument, ExecutionContext executionContext) {
    return jdbcExecutor.fetchNullableOne(plan, argument, executionContext);
  }

  <R> JdbcRow<R> fetchNullableFirst(
      CompiledQueryPlan<R, Object> plan, Object argument, ExecutionContext executionContext) {
    return jdbcExecutor.fetchNullableFirst(plan, argument, executionContext);
  }

  <R> JdbcPageResult<R> fetchPage(
      QueryCompilation<R> content,
      QueryCompilation<Long> count,
      ExecutionContext executionContext) {
    return jdbcExecutor.fetchPage(
        content.plan(), content.argument(), count.plan(), count.argument(), executionContext);
  }

  <R> List<@Nullable R> fetchSliceList(
      CompiledQueryPlan<R, Object> plan,
      Object argument,
      ExecutionContext executionContext,
      int pageSize) {
    return jdbcExecutor.fetchSliceList(plan, argument, executionContext, pageSize);
  }

  <R> QueryCursor<R> cursor(
      CompiledQueryPlan<R, Object> plan, Object argument, ExecutionContext executionContext) {
    return new DefaultQueryCursor<>(jdbcExecutor.openCursor(plan, argument, executionContext));
  }

  <R> QueryCursor<@Nullable R> nullableCursor(
      CompiledQueryPlan<R, Object> plan, Object argument, ExecutionContext executionContext) {
    return DefaultQueryCursor.nullable(jdbcExecutor.openCursor(plan, argument, executionContext));
  }

  void validateRequestedRows(int requestedRows, ExecutionContext executionContext) {
    try {
      jdbcExecutor.validateRequestedRows(requestedRows, executionContext);
    } catch (IllegalArgumentException exception) {
      throw new QueryValidationException(exception.getMessage(), exception);
    }
  }

  private <E> EntityPlanSet<E> requirePlanSet(EntityMeta<E> entity) {
    return planCatalog.require(entity);
  }

  private static void requireValueType(PropertyMeta<?, ?> property, @Nullable Object value) {
    if (!property.javaType().isInstance(value)) {
      String receivedType = value == null ? "null" : value.getClass().getTypeName();
      throw new QueryValidationException(
          "findById id"
              + " for property '"
              + property.name()
              + "' requires "
              + property.javaType().getTypeName()
              + " but received "
              + receivedType);
    }
  }
}
