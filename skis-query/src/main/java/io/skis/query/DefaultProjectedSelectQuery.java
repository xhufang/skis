package io.skis.query;

import io.skis.core.ExecutionContext;
import io.skis.core.ExecutionOptions;
import io.skis.jdbc.CompiledQueryPlan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/** Immutable projection query backed by shared bounded structural plans. */
final class DefaultProjectedSelectQuery<E, R> implements ProjectedSelectQuery<E, R> {

  private final DefaultQueryOperations operations;
  private final EntityPlanSet<E> plans;
  private final QueryTable<E> table;
  private final Projection<E, R> projection;
  private final @Nullable QueryPredicate<E> predicate;
  private final ExecutionContext executionContext;
  private final AtomicReference<@Nullable CompiledQueryPlan<R, Object>> plan;

  DefaultProjectedSelectQuery(
      DefaultQueryOperations operations,
      EntityPlanSet<E> plans,
      QueryTable<E> table,
      Projection<E, R> projection,
      @Nullable QueryPredicate<E> predicate,
      ExecutionContext executionContext) {
    this(
        operations, plans, table, projection, predicate, executionContext, new AtomicReference<>());
  }

  private DefaultProjectedSelectQuery(
      DefaultQueryOperations operations,
      EntityPlanSet<E> plans,
      QueryTable<E> table,
      Projection<E, R> projection,
      @Nullable QueryPredicate<E> predicate,
      ExecutionContext executionContext,
      AtomicReference<@Nullable CompiledQueryPlan<R, Object>> plan) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.plans = Objects.requireNonNull(plans, "plans");
    this.table = Objects.requireNonNull(table, "table");
    this.projection = Objects.requireNonNull(projection, "projection");
    this.predicate = predicate;
    this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
    this.plan = Objects.requireNonNull(plan, "plan");
  }

  @Override
  public ProjectedSelectQuery<E, R> where(QueryPredicate<E> newPredicate) {
    Objects.requireNonNull(newPredicate, "predicate");
    if (predicate != null) {
      throw new QueryValidationException(
          "the single-table DSL accepts one where predicate per query");
    }
    return new DefaultProjectedSelectQuery<>(
        operations, plans, table, projection, newPredicate, executionContext);
  }

  @Override
  public ProjectedSelectQuery<E, R> and(QueryPredicate<E> newPredicate) {
    return chain(newPredicate, true);
  }

  @Override
  public ProjectedSelectQuery<E, R> or(QueryPredicate<E> newPredicate) {
    return chain(newPredicate, false);
  }

  @Override
  public ProjectedSelectQuery<E, R> withOptions(ExecutionOptions executionOptions) {
    ExecutionOptions options = Objects.requireNonNull(executionOptions, "executionOptions");
    if (executionContext.executionOptions().equals(options)) {
      return this;
    }
    return new DefaultProjectedSelectQuery<>(
        operations, plans, table, projection, predicate, ExecutionContext.of(options), plan);
  }

  @Override
  public Optional<R> fetchOne() {
    return operations.fetchOne(plan(), plans.argument(predicate), executionContext);
  }

  @Override
  public List<R> fetchList() {
    return operations.fetchList(plan(), plans.argument(predicate), executionContext);
  }

  private ProjectedSelectQuery<E, R> chain(
      QueryPredicate<E> newPredicate, boolean conjunction) {
    Objects.requireNonNull(newPredicate, "predicate");
    if (predicate == null) {
      throw new QueryValidationException(
          (conjunction ? "and" : "or") + "(...) requires an existing where predicate");
    }
    QueryPredicate<E> combined =
        conjunction ? predicate.and(newPredicate) : predicate.or(newPredicate);
    return new DefaultProjectedSelectQuery<>(
        operations, plans, table, projection, combined, executionContext);
  }

  private CompiledQueryPlan<R, Object> plan() {
    CompiledQueryPlan<R, Object> existing = plan.get();
    if (existing != null) {
      return existing;
    }
    CompiledQueryPlan<R, Object> compiled = plans.projectionPlan(table, projection, predicate);
    CompiledQueryPlan<R, Object> published = plan.compareAndExchange(null, compiled);
    return published == null ? compiled : published;
  }
}
