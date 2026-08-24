package io.skis.query;

import io.skis.dialect.Dialect;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.jdbc.JdbcExecutor;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Default immutable query facade; package-private so users depend on the stable interface. */
final class DefaultQueryOperations implements QueryOperations {

  private final JdbcExecutor jdbcExecutor;
  private final Map<EntityMeta<?>, EntityPlanSet<?>> planSets;

  DefaultQueryOperations(
      EntityRuntimeRegistry runtimeRegistry, Dialect dialect, JdbcExecutor jdbcExecutor) {
    this.jdbcExecutor = Objects.requireNonNull(jdbcExecutor, "jdbcExecutor");
    QueryPlanCompiler compiler = new QueryPlanCompiler(Objects.requireNonNull(dialect, "dialect"));
    Map<EntityMeta<?>, EntityPlanSet<?>> indexed = new IdentityHashMap<>();
    for (EntityRuntimeModel<?> model : runtimeRegistry.models()) {
      EntityPlanSet<?> previous = indexed.put(model.entity(), createPlanSet(model, compiler));
      if (previous != null) {
        throw new IllegalArgumentException(
            "duplicate query plan set for entity '" + model.entity().entityName() + "'");
      }
    }
    this.planSets = Collections.unmodifiableMap(indexed);
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

  @SuppressWarnings("unchecked")
  private <E> EntityPlanSet<E> requirePlanSet(EntityMeta<E> entity) {
    EntityPlanSet<?> plans = planSets.get(entity);
    if (plans == null) {
      throw new QueryValidationException(
          "no generated runtime model is registered for entity '" + entity.entityName() + "'");
    }
    return (EntityPlanSet<E>) plans;
  }

  private static void requireValueType(
      PropertyMeta<?, ?> property, Object value, String description) {
    if (value == null) {
      throw new QueryValidationException(
          description + " for property '" + property.name() + "' must not be null");
    }
    if (!property.javaType().isInstance(value)) {
      throw new QueryValidationException(
          description
              + " for property '"
              + property.name()
              + "' requires "
              + property.javaType().getTypeName()
              + " but received "
              + value.getClass().getTypeName());
    }
  }

  private static <E> EntityPlanSet<E> createPlanSet(
      EntityRuntimeModel<E> model, QueryPlanCompiler compiler) {
    return new EntityPlanSet<>(model, compiler);
  }
}
