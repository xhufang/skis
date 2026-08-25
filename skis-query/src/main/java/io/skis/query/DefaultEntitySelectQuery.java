package io.skis.query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Immutable query value returned by the injected executor. */
final class DefaultEntitySelectQuery<E> implements EntitySelectQuery<E> {

  private final DefaultQueryOperations operations;
  private final EntityPlanSet<E> plans;
  private final QueryTable<E> table;
  private final @Nullable QueryPredicate predicate;

  DefaultEntitySelectQuery(
      DefaultQueryOperations operations,
      EntityPlanSet<E> plans,
      QueryTable<E> table,
      @Nullable QueryPredicate predicate) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.plans = Objects.requireNonNull(plans, "plans");
    this.table = Objects.requireNonNull(table, "table");
    this.predicate = predicate;
  }

  @Override
  public EntitySelectQuery<E> where(QueryPredicate newPredicate) {
    Objects.requireNonNull(newPredicate, "predicate");
    if (predicate != null) {
      throw new QueryValidationException(
          "the single-table DSL accepts one where predicate per query");
    }
    return new DefaultEntitySelectQuery<>(operations, plans, table, newPredicate);
  }

  @Override
  public Optional<E> fetchOne() {
    return operations.fetchOne(plans, table, predicate);
  }

  @Override
  public List<E> fetchList() {
    return operations.fetchList(plans, table, predicate);
  }
}
