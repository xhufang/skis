package io.skis.query;

import io.skis.jdbc.CompiledQueryPlan;
import io.skis.jdbc.JdbcExecutor;
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
    Objects.requireNonNull(entity, "entity");
    EntityPlanSet<E> plans = requirePlanSet(entity);
    PropertyMeta<E, ?> idProperty = plans.findByIdProperty();
    requireValueType(idProperty, id, "findById id");
    return jdbcExecutor.fetchOne(plans.findByIdPlan(), id);
  }

  @Override
  public <E> EntitySelectQuery<E> selectFrom(QueryTable<E> table) {
    Objects.requireNonNull(table, "table");
    EntityPlanSet<E> plans = requirePlanSet(table.entity());
    return new DefaultEntitySelectQuery<>(this, plans, table, null);
  }

  <E> Optional<E> fetchOne(
      EntityPlanSet<E> plans, QueryTable<E> table, @Nullable QueryPredicate predicate) {
    CompiledQueryPlan<E, Object> plan = plans.selectPlan(table, predicate);
    return jdbcExecutor.fetchOne(plan, plans.argument(predicate));
  }

  <E> List<E> fetchList(
      EntityPlanSet<E> plans, QueryTable<E> table, @Nullable QueryPredicate predicate) {
    CompiledQueryPlan<E, Object> plan = plans.selectPlan(table, predicate);
    return jdbcExecutor.fetchList(plan, plans.argument(predicate));
  }

  private <E> EntityPlanSet<E> requirePlanSet(EntityMeta<E> entity) {
    return planCatalog.require(entity);
  }

  private static void requireValueType(
      PropertyMeta<?, ?> property, @Nullable Object value, String description) {
    if (!property.javaType().isInstance(value)) {
      String receivedType = value == null ? "null" : value.getClass().getTypeName();
      throw new QueryValidationException(
          description
              + " for property '"
              + property.name()
              + "' requires "
              + property.javaType().getTypeName()
              + " but received "
              + receivedType);
    }
  }
}
