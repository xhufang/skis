package io.skis.query;

import io.skis.core.ExecutionContext;
import io.skis.core.ExecutionOptions;
import io.skis.jdbc.CompiledQueryPlan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/** Immutable query value returned by the injected executor. */
final class DefaultEntitySelectQuery<E> implements EntitySelectQuery<E> {

  private final DefaultQueryOperations operations;
  private final EntityPlanSet<E> plans;
  private final QueryTable<E> table;
  private final @Nullable QueryPredicate<E> predicate;
  private final ExecutionContext executionContext;
  private final AtomicReference<@Nullable CompiledQueryPlan<E, Object>> plan;

  DefaultEntitySelectQuery(
      DefaultQueryOperations operations,
      EntityPlanSet<E> plans,
      QueryTable<E> table,
      @Nullable QueryPredicate<E> predicate,
      ExecutionContext executionContext) {
    this(operations, plans, table, predicate, executionContext, new AtomicReference<>());
  }

  private DefaultEntitySelectQuery(
      DefaultQueryOperations operations,
      EntityPlanSet<E> plans,
      QueryTable<E> table,
      @Nullable QueryPredicate<E> predicate,
      ExecutionContext executionContext,
      AtomicReference<@Nullable CompiledQueryPlan<E, Object>> plan) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.plans = Objects.requireNonNull(plans, "plans");
    this.table = Objects.requireNonNull(table, "table");
    this.predicate = predicate;
    this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
    this.plan = Objects.requireNonNull(plan, "plan");
  }

  @Override
  public EntitySelectQuery<E> where(QueryPredicate<E> newPredicate) {
    Objects.requireNonNull(newPredicate, "predicate");
    if (predicate != null) {
      throw new QueryValidationException(
          "the single-table DSL accepts one where predicate per query");
    }
    return new DefaultEntitySelectQuery<>(operations, plans, table, newPredicate, executionContext);
  }

  @Override
  public EntitySelectQuery<E> and(QueryPredicate<E> newPredicate) {
    return chain(newPredicate, true);
  }

  @Override
  public EntitySelectQuery<E> or(QueryPredicate<E> newPredicate) {
    return chain(newPredicate, false);
  }

  @Override
  public EntitySelectQuery<E> withOptions(ExecutionOptions executionOptions) {
    ExecutionOptions options = Objects.requireNonNull(executionOptions, "executionOptions");
    if (executionContext.executionOptions().equals(options)) {
      return this;
    }
    return new DefaultEntitySelectQuery<>(
        operations, plans, table, predicate, ExecutionContext.of(options), plan);
  }

  @Override
  public Optional<E> fetchOne() {
    return operations.fetchOne(plan(), plans.argument(predicate), executionContext);
  }

  @Override
  public List<E> fetchList() {
    return operations.fetchList(plan(), plans.argument(predicate), executionContext);
  }

  private EntitySelectQuery<E> chain(QueryPredicate<E> newPredicate, boolean conjunction) {
    Objects.requireNonNull(newPredicate, "predicate");
    if (predicate == null) {
      throw new QueryValidationException(
          (conjunction ? "and" : "or") + "(...) requires an existing where predicate");
    }
    QueryPredicate<E> combined =
        conjunction ? predicate.and(newPredicate) : predicate.or(newPredicate);
    return new DefaultEntitySelectQuery<>(operations, plans, table, combined, executionContext);
  }

  private CompiledQueryPlan<E, Object> plan() {
    CompiledQueryPlan<E, Object> existing = plan.get();
    if (existing != null) {
      return existing;
    }
    CompiledQueryPlan<E, Object> compiled = plans.selectPlan(table, predicate);
    CompiledQueryPlan<E, Object> published = plan.compareAndExchange(null, compiled);
    return published == null ? compiled : published;
  }
}
