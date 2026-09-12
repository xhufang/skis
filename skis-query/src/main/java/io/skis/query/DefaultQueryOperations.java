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
    return DefaultSelectQuery.create(this, plans, table, SelectedResult.entity(table));
  }

  @Override
  public <R> SelectFromStep<R> select(QueryTable<R> table) {
    Objects.requireNonNull(table, "table");
    requirePlanSet(table.entity());
    return new DefaultSelectFromStep<>(this, SelectedResult.entity(table));
  }

  @Override
  public <R> NullableSelectFromStep<R> selectNullable(QueryTable<R> table) {
    Objects.requireNonNull(table, "table");
    requirePlanSet(table.entity());
    return new DefaultNullableSelectFromStep<>(this, SelectedResult.nullableEntity(table));
  }

  @Override
  public <V> SelectFromStep<V> select(NonNullSelectable<V> selectable) {
    return new DefaultSelectFromStep<>(
        this, SelectedResult.requiredScalar(Objects.requireNonNull(selectable, "selectable")));
  }

  @Override
  public <V> NullableSelectFromStep<V> select(Selectable<V> selectable) {
    return new DefaultNullableSelectFromStep<>(
        this, SelectedResult.nullableScalar(Objects.requireNonNull(selectable, "selectable")));
  }

  @Override
  public <V> NullableSelectFromStep<V> selectNullable(NonNullSelectable<V> selectable) {
    return new DefaultNullableSelectFromStep<>(
        this, SelectedResult.nullableScalar(Objects.requireNonNull(selectable, "selectable")));
  }

  @Override
  public <R> SelectFromStep<R> select(ProjectionSelection<R> projection) {
    return new DefaultSelectFromStep<>(
        this, SelectedResult.projection(Objects.requireNonNull(projection, "projection")));
  }

  @Override
  public <R> NullableSelectQuery<?, R> query(SelectDescription<R> description) {
    return query(description, QueryParameters.empty());
  }

  @Override
  public <R> SelectQuery<?, R> query(NonNullSelectDescription<R> description) {
    return query(description, QueryParameters.empty());
  }

  @Override
  public <V> SelectQuery<?, V> query(NonNullSingleColumnSelect<V> description) {
    return query(description, QueryParameters.empty());
  }

  @Override
  public <R> NullableSelectQuery<?, R> query(
      SelectDescription<R> description, QueryParameters parameters) {
    return nullableExecutable(
        Objects.requireNonNull(description, "description").state(),
        Objects.requireNonNull(parameters, "parameters"));
  }

  @Override
  public <R> SelectQuery<?, R> query(
      NonNullSelectDescription<R> description, QueryParameters parameters) {
    return executable(
        Objects.requireNonNull(description, "description").state(),
        Objects.requireNonNull(parameters, "parameters"));
  }

  @Override
  public <V> SelectQuery<?, V> query(
      NonNullSingleColumnSelect<V> description, QueryParameters parameters) {
    return executable(
        Objects.requireNonNull(description, "description").state(),
        Objects.requireNonNull(parameters, "parameters"));
  }

  <F, R> DefaultSelectQuery<F, R> selectFrom(SelectedResult<R> selected, QueryTable<F> table) {
    Objects.requireNonNull(selected, "selected");
    EntityPlanSet<F> plans = requirePlanSet(table.entity());
    return DefaultSelectQuery.create(this, plans, table, selected);
  }

  private <R> DefaultSelectQuery<?, R> executable(
      SelectQueryState<R> state, QueryParameters parameters) {
    return executableCaptured(state, parameters);
  }

  private <F, R> DefaultSelectQuery<F, R> executableCaptured(
      SelectQueryState<R> state, QueryParameters parameters) {
    QueryTable<F> root = state.typedRoot();
    EntityPlanSet<F> plans = requirePlanSet(root.entity());
    return DefaultSelectQuery.create(this, plans, state, parameters);
  }

  private <R> NullableSelectQuery<?, R> nullableExecutable(
      SelectQueryState<R> state, QueryParameters parameters) {
    return nullable(executableCaptured(state, parameters));
  }

  private <F, R> NullableSelectQuery<F, R> nullable(DefaultSelectQuery<F, R> query) {
    return new DefaultNullableSelectQuery<>(this, query);
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
