package io.skis.mutation;

import io.skis.jdbc.JdbcExecutionException;
import io.skis.jdbc.JdbcExecutor;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
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
  public <E> int insert(EntityMeta<E> entity, E value) {
    EntityMutationPlanSet<E> plans = requirePlanSet(entity);
    requireEntityValue(entity, value);
    int affected =
        execute(entity, "insert", () -> jdbcExecutor.executeUpdate(plans.insert(), value));
    if (affected != 1) {
      throw unexpectedRowCount(entity, "insert", affected, "exactly one");
    }
    return affected;
  }

  @Override
  public <E> int updateById(EntityMeta<E> entity, E value) {
    EntityMutationPlanSet<E> plans = requirePlanSet(entity);
    requireEntityValue(entity, value);
    EntityMutationPlanSet.UpdateExecution<E> execution = plans.update(value);
    int affected =
        execute(entity, "updateById", () -> jdbcExecutor.executeUpdate(execution.plan(), value));
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
  public <E> int deleteById(EntityMeta<E> entity, Object id) {
    EntityMutationPlanSet<E> plans = requirePlanSet(entity);
    PropertyMeta<E, ?> idProperty = entity.primaryKey().orElseThrow().properties().getFirst();
    requireValueType(idProperty, id, "deleteById id");
    int affected =
        execute(entity, "deleteById", () -> jdbcExecutor.executeUpdate(plans.delete(), id));
    if (affected > 1) {
      throw unexpectedRowCount(entity, "deleteById", affected, "at most one");
    }
    return affected;
  }

  private <E> EntityMutationPlanSet<E> requirePlanSet(EntityMeta<E> entity) {
    return planCatalog.require(entity);
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

  private static void requireValueType(
      PropertyMeta<?, ?> property, Object value, String description) {
    if (!property.javaType().isInstance(value)) {
      throw new MutationException(
          description
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
              Objects.requireNonNull(failure.getCause(), "JDBC failure cause"));
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
