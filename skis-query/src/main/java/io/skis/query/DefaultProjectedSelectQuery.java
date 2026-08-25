package io.skis.query;

import io.skis.jdbc.CompiledQueryPlan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/** Immutable projection query backed by shared bounded structural plans. */
final class DefaultProjectedSelectQuery<E, R> implements ProjectedSelectQuery<R> {

  private final DefaultQueryOperations operations;
  private final EntityPlanSet<E> plans;
  private final QueryTable<E> table;
  private final Projection<E, R> projection;
  private final @Nullable QueryPredicate predicate;
  private final AtomicReference<@Nullable CompiledQueryPlan<R, Object>> plan =
      new AtomicReference<>();

  DefaultProjectedSelectQuery(
      DefaultQueryOperations operations,
      EntityPlanSet<E> plans,
      QueryTable<E> table,
      Projection<E, R> projection,
      @Nullable QueryPredicate predicate) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.plans = Objects.requireNonNull(plans, "plans");
    this.table = Objects.requireNonNull(table, "table");
    this.projection = Objects.requireNonNull(projection, "projection");
    this.predicate = predicate;
  }

  @Override
  public ProjectedSelectQuery<R> where(QueryPredicate newPredicate) {
    Objects.requireNonNull(newPredicate, "predicate");
    if (predicate != null) {
      throw new QueryValidationException(
          "the single-table DSL accepts one where predicate per query");
    }
    return new DefaultProjectedSelectQuery<>(operations, plans, table, projection, newPredicate);
  }

  @Override
  public Optional<R> fetchOne() {
    return operations.fetchOne(plan(), plans.argument(predicate));
  }

  @Override
  public List<R> fetchList() {
    return operations.fetchList(plan(), plans.argument(predicate));
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
