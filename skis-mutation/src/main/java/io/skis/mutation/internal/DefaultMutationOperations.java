package io.skis.mutation.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.QueryValidationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.spi.ExecutionContext;
import io.skis.jdbc.internal.CompiledMutationPlan;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.mutation.MutationException;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.OptimisticLockException;
import io.skis.query.QueryTable;
import java.util.Objects;

/** Default immutable entity mutation facade. */
final class DefaultMutationOperations implements MutationOperations {

  private final JdbcExecutor jdbcExecutor;
  private final MutationPlanCatalog planCatalog;

  DefaultMutationOperations(MutationPlanCatalog planCatalog, JdbcExecutor jdbcExecutor) {
    this.planCatalog = Objects.requireNonNull(planCatalog, "planCatalog");
    this.jdbcExecutor = Objects.requireNonNull(jdbcExecutor, "jdbcExecutor");
  }

  @Override
  public <E> int insert(QueryTable<E> table, E value) {
    return insert(table, value, ExecutionContext.EMPTY);
  }

  @Override
  public <E> int insert(QueryTable<E> table, E value, ExecutionOptions executionOptions) {
    return insert(
        table,
        value,
        ExecutionContext.of(Objects.requireNonNull(executionOptions, "executionOptions")));
  }

  private <E> int insert(QueryTable<E> table, E value, ExecutionContext context) {
    EntityMeta<E> entity = requireEntity(table);
    EntityMutationPlanSet<E> plans = requirePlanSet(entity);
    requireEntityValue(entity, value);
    int affected = executeUpdate(plans.insert(), value, context);
    if (affected != 1) {
      throw unexpectedRowCount(entity, "insert", affected, "exactly one");
    }
    return affected;
  }

  @Override
  public <E> int updateById(QueryTable<E> table, E value) {
    return updateById(table, value, ExecutionContext.EMPTY);
  }

  @Override
  public <E> int updateById(QueryTable<E> table, E value, ExecutionOptions executionOptions) {
    return updateById(
        table,
        value,
        ExecutionContext.of(Objects.requireNonNull(executionOptions, "executionOptions")));
  }

  private <E> int updateById(QueryTable<E> table, E value, ExecutionContext context) {
    EntityMeta<E> entity = requireEntity(table);
    EntityMutationPlanSet<E> plans = requirePlanSet(entity);
    requireEntityValue(entity, value);
    EntityMutationPlanSet.UpdateExecution<E> execution = plans.update(value);
    int affected = executeUpdate(execution.plan(), value, context);
    if (execution.versionChecked() && affected == 0) {
      throw OptimisticLockException.of(
          "optimistic update conflict for entity '" + entity.entityName() + "'");
    }
    if (affected > 1) {
      throw unexpectedRowCount(entity, "updateById", affected, "at most one");
    }
    return affected;
  }

  @Override
  public <E> int deleteById(QueryTable<E> table, Object id) {
    return deleteById(table, id, ExecutionContext.EMPTY);
  }

  @Override
  public <E> int deleteById(QueryTable<E> table, Object id, ExecutionOptions executionOptions) {
    return deleteById(
        table,
        id,
        ExecutionContext.of(Objects.requireNonNull(executionOptions, "executionOptions")));
  }

  private <E> int deleteById(QueryTable<E> table, Object id, ExecutionContext context) {
    EntityMeta<E> entity = requireEntity(table);
    EntityMutationPlanSet<E> plans = requirePlanSet(entity);
    PropertyMeta<E, ?> idProperty = entity.primaryKey().orElseThrow().properties().getFirst();
    requireValueType(idProperty, id);
    int affected = executeUpdate(plans.delete(), id, context);
    if (affected > 1) {
      throw unexpectedRowCount(entity, "deleteById", affected, "at most one");
    }
    return affected;
  }

  private <E> EntityMutationPlanSet<E> requirePlanSet(EntityMeta<E> entity) {
    return planCatalog.require(entity);
  }

  private static <E> EntityMeta<E> requireEntity(QueryTable<E> table) {
    return Objects.requireNonNull(table, "table").entity();
  }

  private static <E> void requireEntityValue(EntityMeta<E> entity, E value) {
    Objects.requireNonNull(value, "value");
    if (!entity.javaType().isInstance(value)) {
      throw MutationException.of(
          SkisErrorCode.MUTATION_ENTITY_TYPE_MISMATCH,
          "mutation for entity '"
              + entity.entityName()
              + "' requires "
              + entity.javaType().getTypeName());
    }
  }

  private static void requireValueType(PropertyMeta<?, ?> property, Object value) {
    if (!property.javaType().isInstance(value)) {
      throw MutationException.of(
          SkisErrorCode.MUTATION_VALUE_TYPE_MISMATCH,
          "deleteById id"
              + " for property '"
              + property.name()
              + "' requires "
              + property.javaType().getTypeName());
    }
  }

  private <P> int executeUpdate(
      CompiledMutationPlan<P> plan, P parameters, ExecutionContext context) {
    try {
      return jdbcExecutor.executeUpdate(plan, parameters, context);
    } catch (QueryValidationException failure) {
      if (failure.code() != SkisErrorCode.QUERY_PARAMETER_VALUE) {
        throw failure;
      }
      throw MutationException.of(
          SkisErrorCode.MUTATION_VALUE_TYPE_MISMATCH,
          "mutation value violates a generated property type or nullability contract",
          failure);
    }
  }

  private static MutationException unexpectedRowCount(
      EntityMeta<?> entity, String operation, int affected, String expected) {
    return MutationException.of(
        SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
        operation
            + " for entity '"
            + entity.entityName()
            + "' affected "
            + affected
            + " rows; expected "
            + expected);
  }
}
