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
  private final ProjectionRegistry projectionRegistry;

  DefaultQueryOperations(
      QueryPlanCatalog planCatalog,
      ProjectionRegistry projectionRegistry,
      JdbcExecutor jdbcExecutor) {
    this.planCatalog = Objects.requireNonNull(planCatalog, "planCatalog");
    this.projectionRegistry = Objects.requireNonNull(projectionRegistry, "projectionRegistry");
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
    return DefaultSelectQuery.entity(this, plans, table);
  }

  @Override
  public <E, V> SelectFromStep<E, V> select(NonNullQueryColumn<E, V> column) {
    return new DefaultSelectFromStep<>(this, Projection.scalar(column));
  }

  @Override
  public <E, V> NullableSelectFromStep<E, V> select(NullableQueryColumn<E, V> column) {
    return new DefaultNullableSelectFromStep<>(this, Projection.nullableScalar(column));
  }

  @Override
  public <E, R> SelectQuery<E, R> selectProjection(QueryTable<E> table, Class<R> projectionType) {
    Objects.requireNonNull(table, "table");
    Projection<E, R> projection = projectionRegistry.require(table, projectionType);
    return selectFrom(projection, table);
  }

  <E, R> DefaultSelectQuery<E, R> selectFrom(Projection<E, R> projection, QueryTable<E> table) {
    projection.validateFrom(table);
    EntityPlanSet<E> plans = requirePlanSet(table.entity());
    return DefaultSelectQuery.projection(this, plans, table, projection);
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
