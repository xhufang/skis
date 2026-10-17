package io.skis.mutation;

import io.skis.core.ExecutionContext;
import io.skis.core.ExecutionOptions;
import io.skis.jdbc.JdbcExecutionException;
import io.skis.jdbc.JdbcExecutor;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
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
    int affected =
        execute(entity, "insert", () -> jdbcExecutor.executeUpdate(plans.insert(), value, context));
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
    int affected =
        execute(
            entity,
            "updateById",
            () -> jdbcExecutor.executeUpdate(execution.plan(), value, context));
    if (execution.versionChecked() && affected == 0) {
      throw new OptimisticLockException(
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
    int affected =
        execute(
            entity, "deleteById", () -> jdbcExecutor.executeUpdate(plans.delete(), id, context));
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
      throw new MutationException(
          "mutation for entity '"
              + entity.entityName()
              + "' requires "
              + entity.javaType().getTypeName());
    }
  }

  private static void requireValueType(PropertyMeta<?, ?> property, Object value) {
    if (!property.javaType().isInstance(value)) {
      throw new MutationException(
          "deleteById id"
              + " for property '"
              + property.name()
              + "' requires "
              + property.javaType().getTypeName());
    }
  }

  private static <E> int execute(EntityMeta<E> entity, String operation, MutationWork work) {
    try {
      return work.execute();
    } catch (JdbcExecutionException failure) {
      MutationException translated =
          new MutationException(
              "failed to "
                  + operation
                  + " entity '"
                  + entity.entityName()
                  + "'; "
                  + failure.getMessage(),
              Objects.requireNonNull(failure.getCause(), "JDBC failure cause"),
              failure.category());
      for (Throwable suppressed : failure.getSuppressed()) {
        translated.addSuppressed(suppressed);
      }
      throw translated;
    }
  }

  private static MutationException unexpectedRowCount(
      EntityMeta<?> entity, String operation, int affected, String expected) {
    return new MutationException(
        operation
            + " for entity '"
            + entity.entityName()
            + "' affected "
            + affected
            + " rows; expected "
            + expected);
  }

  @FunctionalInterface
  private interface MutationWork {
    int execute();
  }
}
