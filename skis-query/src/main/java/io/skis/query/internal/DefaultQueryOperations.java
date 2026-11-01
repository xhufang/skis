package io.skis.query.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.QueryValidationException;
import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.StatementPurpose;
import io.skis.jdbc.internal.CompiledQueryPlan;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.jdbc.internal.JdbcPageResult;
import io.skis.jdbc.internal.JdbcRow;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.query.ExecutableSelectQuery;
import io.skis.query.ExecutableSingleColumnSelect;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryCursor;
import io.skis.query.QueryOperations;
import io.skis.query.QueryRequest;
import io.skis.query.QueryTable;
import io.skis.query.Row2;
import io.skis.query.Row3;
import io.skis.query.SelectFromStep;
import io.skis.query.Selectable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Default immutable query facade; package-private so users depend on the stable interface. */
final class DefaultQueryOperations implements QueryOperations {

  private final JdbcExecutor jdbcExecutor;
  private final QueryPlanCatalog planCatalog;
  private final ExecutionListenerDispatcher listenerDispatcher;
  private final String dialectId;

  DefaultQueryOperations(QueryPlanCatalog planCatalog, JdbcExecutor jdbcExecutor) {
    this.planCatalog = Objects.requireNonNull(planCatalog, "planCatalog");
    this.jdbcExecutor = Objects.requireNonNull(jdbcExecutor, "jdbcExecutor");
    this.listenerDispatcher = planCatalog.listenerDispatcher();
    this.dialectId = planCatalog.dialectId();
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
    OperationObservation observation = startObservation(ExecutionOperationKind.FIND_BY_ID);
    try {
      EntityMeta<E> entity = Objects.requireNonNull(table, "table").entity();
      EntityPlanSet<E> plans = requirePlanSet(entity);
      PropertyMeta<E, ?> idProperty = plans.findByIdProperty();
      requireValueType(idProperty, id);
      Optional<E> result =
          jdbcExecutor.fetchOne(
              plans.findByIdPlan(), id, executionContext, observation, StatementPurpose.PRIMARY);
      completeSucceeded(observation);
      return result;
    } catch (RuntimeException | Error failure) {
      completeFailed(observation, failure);
      throw failure;
    }
  }

  @Override
  public <E> ExecutableSelectQuery<E> selectFrom(QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    return selectFrom(SelectedResult.entity(table), QueryRelations.of(table));
  }

  @Override
  public <E> SelectFromStep<ExecutableSelectQuery<E>> select(QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    requirePlanSet(table.entity());
    return selectFrom(SelectedResult.entity(table));
  }

  @Override
  public <E> SelectFromStep<ExecutableSelectQuery<@Nullable E>> selectNullable(
      QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    requirePlanSet(table.entity());
    return this.<@Nullable E>selectFrom(SelectedResult.nullableEntity(table));
  }

  @Override
  public <V> SelectFromStep<ExecutableSingleColumnSelect<V>> select(
      NonNullSelectable<V> selectable) {
    return scalarFrom(
        SelectedResult.requiredScalar(Objects.requireNonNull(selectable, "selectable")));
  }

  @Override
  public <V> SelectFromStep<ExecutableSingleColumnSelect<@Nullable V>> select(
      Selectable<V> selectable) {
    SelectedResult<@Nullable V> selected =
        SelectedResult.nullableScalar(Objects.requireNonNull(selectable, "selectable"));
    return this.<@Nullable V>scalarFrom(selected);
  }

  @Override
  public <V> SelectFromStep<ExecutableSingleColumnSelect<@Nullable V>> selectNullable(
      NonNullSelectable<V> selectable) {
    SelectedResult<@Nullable V> selected =
        SelectedResult.nullableScalar(Objects.requireNonNull(selectable, "selectable"));
    return this.<@Nullable V>scalarFrom(selected);
  }

  @Override
  public <R> SelectFromStep<ExecutableSelectQuery<R>> select(ProjectionSelection<R> projection) {
    return selectFrom(SelectedResult.projection(Objects.requireNonNull(projection, "projection")));
  }

  @Override
  public <R> SelectFromStep<ExecutableSelectQuery<R>> select(ProjectionType<R> projection) {
    return selectFrom(
        SelectedResult.specialProjection(Objects.requireNonNull(projection, "projection")));
  }

  @Override
  public <T extends @Nullable Object> T run(QueryRequest<T> request) {
    Objects.requireNonNull(request, "request");
    if (!(request instanceof DefaultQueryRequest<?, ?> builtIn)) {
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_UNSUPPORTED_STRUCTURE, "execution request was not created by SKIS");
    }
    @SuppressWarnings("unchecked")
    DefaultQueryRequest<?, T> typed = (DefaultQueryRequest<?, T>) builtIn;
    return typed.run(this);
  }

  @SuppressWarnings("Convert2Diamond")
  private <R extends @Nullable Object> SelectFromStep<ExecutableSingleColumnSelect<R>> scalarFrom(
      SelectedResult<R> selected) {
    return QueryDsl.fromStep(
        root -> {
          EntityPlanSet<?> plans = fastPlans(root);
          return SelectQueryState.entry(
              selected,
              root,
              (state, values) ->
                  new DefaultExecutableSingleColumnSelect<R>(
                      this,
                      planCatalog.compiler(),
                      plans,
                      state,
                      values,
                      ExecutionContext.EMPTY,
                      Set.of()));
        });
  }

  private <R extends @Nullable Object> SelectFromStep<ExecutableSelectQuery<R>> selectFrom(
      SelectedResult<R> selected) {
    return QueryDsl.fromStep(root -> selectFrom(selected, root));
  }

  @SuppressWarnings("Convert2Diamond")
  private <R extends @Nullable Object> DefaultExecutableSelectQuery<R> selectFrom(
      SelectedResult<R> selected, QueryRelation root) {
    EntityPlanSet<?> plans = fastPlans(root);
    return SelectQueryState.entry(
        selected,
        root,
        (state, values) ->
            new DefaultExecutableSelectQuery<R>(
                this, planCatalog.compiler(), plans, state, values, ExecutionContext.EMPTY));
  }

  private @Nullable EntityPlanSet<?> fastPlans(QueryRelation root) {
    QueryTable<?> table = root.entityTable();
    return table == null ? null : requirePlanSet(table.entity());
  }

  /**
   * Adapts one model for a single execution. Option-independent analyses and plans live on the
   * model itself, so repeated requests reuse them while options remain per execution.
   */
  <R extends @Nullable Object> DefaultExecutableSelectQuery<R> executable(
      AbstractSelectQuery<R, ?> query, ExecutionOptions options) {
    QueryPlanCompiler compiler = planCatalog.compiler();
    QueryTable<?> root = query.state.entityRootOrNull();
    return new DefaultExecutableSelectQuery<>(
        this,
        compiler,
        root == null ? null : requirePlanSet(root.entity()),
        query.state,
        query.parameters,
        ExecutionContext.of(options),
        query.orderingOnlyParameters,
        query.compiled(compiler));
  }

  <R extends @Nullable Object> List<R> fetchList(
      CompiledQueryPlan<R, Object> plan,
      Object argument,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation) {
    return jdbcExecutor.fetchList(
        plan, argument, executionContext, observation, StatementPurpose.PRIMARY);
  }

  <R extends @Nullable Object> JdbcRow<R> fetchNullableOne(
      CompiledQueryPlan<R, Object> plan,
      Object argument,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation) {
    return jdbcExecutor.fetchNullableOne(
        plan, argument, executionContext, observation, StatementPurpose.PRIMARY);
  }

  <R extends @Nullable Object> JdbcRow<R> fetchNullableFirst(
      CompiledQueryPlan<R, Object> plan,
      Object argument,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation) {
    return jdbcExecutor.fetchNullableFirst(
        plan, argument, executionContext, observation, StatementPurpose.PRIMARY);
  }

  <R extends @Nullable Object> JdbcPageResult<R> fetchPage(
      QueryCompilation<R> content,
      QueryCompilation<Long> count,
      ExecutionContext executionContext,
      @Nullable OperationObservation observation) {
    return jdbcExecutor.fetchPage(
        content.plan(),
        content.argument(),
        count.plan(),
        count.argument(),
        executionContext,
        observation);
  }

  <R extends @Nullable Object> List<R> fetchSliceList(
      CompiledQueryPlan<R, Object> plan,
      Object argument,
      ExecutionContext executionContext,
      int pageSize,
      @Nullable OperationObservation observation) {
    return jdbcExecutor.fetchSliceList(
        plan, argument, executionContext, pageSize, observation, StatementPurpose.PRIMARY);
  }

  <R extends @Nullable Object> QueryCursor<R> cursor(
      CompiledQueryPlan<R, Object> plan,
      Object argument,
      ExecutionContext executionContext,
      boolean nullableResult,
      @Nullable OperationObservation observation) {
    return new DefaultQueryCursor<>(
        jdbcExecutor.openCursor(
            plan, argument, executionContext, observation, StatementPurpose.PRIMARY),
        nullableResult);
  }

  @Nullable OperationObservation startObservation(ExecutionOperationKind operationKind) {
    return listenerDispatcher.startOperation(operationKind, dialectId);
  }

  void completeSucceeded(@Nullable OperationObservation observation) {
    if (observation != null) {
      observation.completeSucceeded(ExecutionTermination.COMPLETED);
    }
  }

  void completeFailed(@Nullable OperationObservation observation, Throwable failure) {
    if (observation != null) {
      observation.completeFailed(failure, SkisDiagnosticStage.EXECUTION);
    }
  }

  void validateRequestedRows(int requestedRows, ExecutionContext executionContext) {
    jdbcExecutor.validateRequestedRows(requestedRows, executionContext);
  }

  private <E> EntityPlanSet<E> requirePlanSet(EntityMeta<E> entity) {
    return planCatalog.require(entity);
  }

  private static void requireValueType(PropertyMeta<?, ?> property, @Nullable Object value) {
    if (!property.javaType().isInstance(value)) {
      String receivedType = value == null ? "null" : value.getClass().getTypeName();
      throw QueryValidationException.of(
          SkisErrorCode.QUERY_TYPE_MISMATCH,
          "findById id for property '"
              + property.name()
              + "' requires "
              + property.javaType().getTypeName()
              + " but received "
              + receivedType);
    }
  }

  @Override
  public <A, B> SelectFromStep<ExecutableSelectQuery<Row2<@Nullable A, @Nullable B>>> select(
      Selectable<A> first, Selectable<B> second) {
    return select(QueryDsl.<@Nullable A, @Nullable B>row2(first, second, 3));
  }

  @Override
  public <A, B> SelectFromStep<ExecutableSelectQuery<Row2<A, @Nullable B>>> select(
      NonNullSelectable<A> first, Selectable<B> second) {
    return select(QueryDsl.<A, @Nullable B>row2(first, second, 2));
  }

  @Override
  public <A, B> SelectFromStep<ExecutableSelectQuery<Row2<@Nullable A, B>>> select(
      Selectable<A> first, NonNullSelectable<B> second) {
    return select(QueryDsl.<@Nullable A, B>row2(first, second, 1));
  }

  @Override
  public <A, B> SelectFromStep<ExecutableSelectQuery<Row2<A, B>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second) {
    return select(QueryDsl.row2(first, second, 0));
  }

  @Override
  public <A, B, C>
      SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, @Nullable B, @Nullable C>>> select(
          Selectable<A> first, Selectable<B> second, Selectable<C> third) {
    return select(QueryDsl.<@Nullable A, @Nullable B, @Nullable C>row3(first, second, third, 7));
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, @Nullable B, @Nullable C>>> select(
      NonNullSelectable<A> first, Selectable<B> second, Selectable<C> third) {
    return select(QueryDsl.<A, @Nullable B, @Nullable C>row3(first, second, third, 6));
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, B, @Nullable C>>> select(
      Selectable<A> first, NonNullSelectable<B> second, Selectable<C> third) {
    return select(QueryDsl.<@Nullable A, B, @Nullable C>row3(first, second, third, 5));
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, B, @Nullable C>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second, Selectable<C> third) {
    return select(QueryDsl.<A, B, @Nullable C>row3(first, second, third, 4));
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, @Nullable B, C>>> select(
      Selectable<A> first, Selectable<B> second, NonNullSelectable<C> third) {
    return select(QueryDsl.<@Nullable A, @Nullable B, C>row3(first, second, third, 3));
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, @Nullable B, C>>> select(
      NonNullSelectable<A> first, Selectable<B> second, NonNullSelectable<C> third) {
    return select(QueryDsl.<A, @Nullable B, C>row3(first, second, third, 2));
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<@Nullable A, B, C>>> select(
      Selectable<A> first, NonNullSelectable<B> second, NonNullSelectable<C> third) {
    return select(QueryDsl.<@Nullable A, B, C>row3(first, second, third, 1));
  }

  @Override
  public <A, B, C> SelectFromStep<ExecutableSelectQuery<Row3<A, B, C>>> select(
      NonNullSelectable<A> first, NonNullSelectable<B> second, NonNullSelectable<C> third) {
    return select(QueryDsl.row3(first, second, third, 0));
  }
}
