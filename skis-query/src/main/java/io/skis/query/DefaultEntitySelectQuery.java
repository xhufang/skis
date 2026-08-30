package io.skis.query;

import io.skis.core.ExecutionContext;
import io.skis.core.ExecutionOptions;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Immutable query value returned by the injected executor. */
final class DefaultEntitySelectQuery<E> implements EntitySelectQuery<E> {

  private final DefaultQueryOperations operations;
  private final EntityPlanSet<E> plans;
  private final QueryTable<E> table;
  private final @Nullable QueryPredicate<E> predicate;
  private final ExecutionContext executionContext;

  DefaultEntitySelectQuery(
      DefaultQueryOperations operations,
      EntityPlanSet<E> plans,
      QueryTable<E> table,
      @Nullable QueryPredicate<E> predicate,
      ExecutionContext executionContext) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.plans = Objects.requireNonNull(plans, "plans");
    this.table = Objects.requireNonNull(table, "table");
    this.predicate = predicate;
    this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
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
  public EntitySelectQuery<E> withOptions(ExecutionOptions executionOptions) {
    ExecutionOptions options = Objects.requireNonNull(executionOptions, "executionOptions");
    if (executionContext.executionOptions().equals(options)) {
      return this;
    }
    return new DefaultEntitySelectQuery<>(
        operations, plans, table, predicate, ExecutionContext.of(options));
  }

  @Override
  public Optional<E> fetchOne() {
    return operations.fetchOne(plans, table, predicate, executionContext);
  }

  @Override
  public List<E> fetchList() {
    return operations.fetchList(plans, table, predicate, executionContext);
  }
}
