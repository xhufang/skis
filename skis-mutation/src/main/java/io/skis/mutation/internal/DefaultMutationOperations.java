package io.skis.mutation.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.MutationException;
import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.ExecutionListenerDispatcher;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.ExecutionTermination;
import io.skis.core.spi.StatementPurpose;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.PropertyMeta;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.OptimisticLockException;
import io.skis.query.QueryTable;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Default immutable entity mutation facade. */
final class DefaultMutationOperations implements MutationOperations {

  private final JdbcExecutor jdbcExecutor;
  private final MutationPlanCatalog planCatalog;
  private final ExecutionListenerDispatcher listenerDispatcher;
  private final String dialectId;

  DefaultMutationOperations(MutationPlanCatalog planCatalog, JdbcExecutor jdbcExecutor) {
    this.planCatalog = Objects.requireNonNull(planCatalog, "planCatalog");
    this.jdbcExecutor = Objects.requireNonNull(jdbcExecutor, "jdbcExecutor");
    this.listenerDispatcher = planCatalog.listenerDispatcher();
    this.dialectId = planCatalog.dialectId();
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
    OperationObservation observation = startObservation(ExecutionOperationKind.INSERT);
    try {
      EntityMeta<E> entity = requireEntity(table);
      EntityMutationPlanSet<E> plans = requirePlanSet(entity);
      requireEntityValue(entity, value);
      int affected =
          jdbcExecutor.executeUpdate(
              plans.insert(), value, context, observation, StatementPurpose.PRIMARY);
      if (affected != 1) {
        throw unexpectedRowCount(entity, "insert", affected, "exactly one");
      }
      completeSucceeded(observation);
      return affected;
    } catch (RuntimeException | Error failure) {
      completeFailed(observation, failure);
      throw failure;
    }
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
    OperationObservation observation = startObservation(ExecutionOperationKind.UPDATE_BY_ID);
    try {
      EntityMeta<E> entity = requireEntity(table);
      EntityMutationPlanSet<E> plans = requirePlanSet(entity);
      requireEntityValue(entity, value);
      EntityMutationPlanSet.UpdateExecution<E> execution = plans.update(value);
      int affected =
          jdbcExecutor.executeUpdate(
              execution.plan(), value, context, observation, StatementPurpose.PRIMARY);
      if (execution.versionChecked() && affected == 0) {
        throw OptimisticLockException.of(
            "optimistic update conflict for entity '" + entity.entityName() + "'");
      }
      if (affected > 1) {
        throw unexpectedRowCount(entity, "updateById", affected, "at most one");
      }
      completeSucceeded(observation);
      return affected;
    } catch (RuntimeException | Error failure) {
      completeFailed(observation, failure);
      throw failure;
    }
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
    OperationObservation observation = startObservation(ExecutionOperationKind.DELETE_BY_ID);
    try {
      EntityMeta<E> entity = requireEntity(table);
      EntityMutationPlanSet<E> plans = requirePlanSet(entity);
      PropertyMeta<E, ?> idProperty = entity.primaryKey().orElseThrow().properties().getFirst();
      requireValueType(idProperty, id);
      int affected =
          jdbcExecutor.executeUpdate(
              plans.delete(), id, context, observation, StatementPurpose.PRIMARY);
      if (affected > 1) {
        throw unexpectedRowCount(entity, "deleteById", affected, "at most one");
      }
      completeSucceeded(observation);
      return affected;
    } catch (RuntimeException | Error failure) {
      completeFailed(observation, failure);
      throw failure;
    }
  }

  private @Nullable OperationObservation startObservation(ExecutionOperationKind operationKind) {
    return listenerDispatcher.startOperation(operationKind, dialectId);
  }

  private static void completeSucceeded(@Nullable OperationObservation observation) {
    if (observation != null) {
      observation.completeSucceeded(ExecutionTermination.COMPLETED);
    }
  }

  private static void completeFailed(
      @Nullable OperationObservation observation, Throwable failure) {
    if (observation != null) {
      observation.completeFailed(failure, SkisDiagnosticStage.EXECUTION);
    }
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
