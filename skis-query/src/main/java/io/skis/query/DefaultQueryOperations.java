package io.skis.query;

import io.skis.core.ExecutionOptions;
import io.skis.core.spi.ExecutionContext;
import io.skis.jdbc.internal.CompiledQueryPlan;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.internal.JdbcPageResult;
import io.skis.jdbc.internal.JdbcRow;
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
  public <E> Optional<E> findById(QueryTable<E> table, Object id) {
    return findById(table, id, ExecutionContext.EMPTY);
  }

  @Override
  public <E> Optional<E> findById(
      QueryTable<E> table, Object id, ExecutionOptions executionOptions) {
    return findById(
        table,
        id,
        ExecutionContext.of(Objects.requireNonNull(executionOptions, "executionOptions")));
  }

  private <E> Optional<E> findById(
      QueryTable<E> table, Object id, ExecutionContext executionContext) {
    EntityMeta<E> entity = Objects.requireNonNull(table, "table").entity();
    EntityPlanSet<E> plans = requirePlanSet(entity);
    PropertyMeta<E, ?> idProperty = plans.findByIdProperty();
    requireValueType(idProperty, id);
    return jdbcExecutor.fetchOne(plans.findByIdPlan(), id, executionContext);
  }

  @Override
  public <E> SelectQuery<E> selectFrom(QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    EntityPlanSet<E> plans = requirePlanSet(table.entity());
    return DefaultSelectQuery.create(this, plans, table, SelectedResult.entity(table));
  }

  @Override
  public <E> SelectFromStep<SelectQuery<E>> select(QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    requirePlanSet(table.entity());
    return new DefaultSelectFromStep<>(this, SelectedResult.entity(table));
  }

  @Override
  public <E> SelectFromStep<SelectQuery<@Nullable E>> selectNullable(QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    requirePlanSet(table.entity());
    return new DefaultSelectFromStep<@Nullable E>(this, SelectedResult.nullableEntity(table));
  }

  @Override
  public <V> SelectFromStep<SelectQuery<V>> select(NonNullSelectable<V> selectable) {
    return new DefaultSelectFromStep<>(
        this, SelectedResult.requiredScalar(Objects.requireNonNull(selectable, "selectable")));
  }

  @Override
  public <V> SelectFromStep<SelectQuery<@Nullable V>> select(Selectable<V> selectable) {
    return new DefaultSelectFromStep<@Nullable V>(
        this, SelectedResult.nullableScalar(Objects.requireNonNull(selectable, "selectable")));
  }

  @Override
  public <V> SelectFromStep<SelectQuery<@Nullable V>> selectNullable(
      NonNullSelectable<V> selectable) {
    return new DefaultSelectFromStep<@Nullable V>(
        this, SelectedResult.nullableScalar(Objects.requireNonNull(selectable, "selectable")));
  }

  @Override
  public <R> SelectFromStep<SelectQuery<R>> select(ProjectionSelection<R> projection) {
    return new DefaultSelectFromStep<>(
        this, SelectedResult.projection(Objects.requireNonNull(projection, "projection")));
  }

  @Override
  public <R> SelectFromStep<SelectQuery<R>> select(ProjectionType<R> projection) {
    return new DefaultSelectFromStep<>(
        this, SelectedResult.specialProjection(Objects.requireNonNull(projection, "projection")));
  }

  @Override
  public <R extends @Nullable Object> SelectQuery<R> query(SelectDescription<R> description) {
    return executable(
        Objects.requireNonNull(description, "description").state(), QueryParameters.empty());
  }

  <R extends @Nullable Object> DefaultSelectQuery<R> selectFrom(
      SelectedResult<R> selected, QueryTable<?> table) {
    Objects.requireNonNull(selected, "selected");
    Objects.requireNonNull(table, "table");
    EntityPlanSet<?> plans = requirePlanSet(table.entity());
    return DefaultSelectQuery.create(this, plans, table, selected);
  }

  <R extends @Nullable Object> DefaultSelectQuery<R> selectFrom(
      SelectedResult<R> selected, DerivedRelation relation) {
    Objects.requireNonNull(selected, "selected");
    Objects.requireNonNull(relation, "relation");
    return DefaultSelectQuery.create(
        this,
        planCatalog.compiler(),
        SelectQueryState.create(selected, relation),
        QueryParameters.empty());
  }

  private <R extends @Nullable Object> DefaultSelectQuery<R> executable(
      SelectQueryState<R> state, QueryParameters parameters) {
    QueryTable<?> entityRoot = state.entityRootOrNull();
    if (entityRoot == null) {
      return DefaultSelectQuery.create(this, planCatalog.compiler(), state, parameters);
    }
    return DefaultSelectQuery.create(this, requirePlanSet(entityRoot.entity()), state, parameters);
  }

  <R extends @Nullable Object> List<R> fetchList(
      CompiledQueryPlan<R, Object> plan, Object argument, ExecutionContext executionContext) {
    return jdbcExecutor.fetchList(plan, argument, executionContext);
  }

  <R extends @Nullable Object> JdbcRow<R> fetchNullableOne(
      CompiledQueryPlan<R, Object> plan, Object argument, ExecutionContext executionContext) {
    return jdbcExecutor.fetchNullableOne(plan, argument, executionContext);
  }

  <R extends @Nullable Object> JdbcRow<R> fetchNullableFirst(
      CompiledQueryPlan<R, Object> plan, Object argument, ExecutionContext executionContext) {
    return jdbcExecutor.fetchNullableFirst(plan, argument, executionContext);
  }

  <R extends @Nullable Object> JdbcPageResult<R> fetchPage(
      QueryCompilation<R> content,
      QueryCompilation<Long> count,
      ExecutionContext executionContext) {
    return jdbcExecutor.fetchPage(
        content.plan(), content.argument(), count.plan(), count.argument(), executionContext);
  }

  <R extends @Nullable Object> List<R> fetchSliceList(
      CompiledQueryPlan<R, Object> plan,
      Object argument,
      ExecutionContext executionContext,
      int pageSize) {
    return jdbcExecutor.fetchSliceList(plan, argument, executionContext, pageSize);
  }

  <R extends @Nullable Object> QueryCursor<R> cursor(
      CompiledQueryPlan<R, Object> plan,
      Object argument,
      ExecutionContext executionContext,
      boolean nullableResult) {
    return new DefaultQueryCursor<>(
        jdbcExecutor.openCursor(plan, argument, executionContext), nullableResult);
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
          "findById id for property '"
              + property.name()
              + "' requires "
              + property.javaType().getTypeName()
              + " but received "
              + receivedType);
    }
  }
}
