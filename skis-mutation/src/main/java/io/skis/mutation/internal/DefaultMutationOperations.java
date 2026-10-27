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
import io.skis.mapping.spi.InsertArguments;
import io.skis.metadata.EntityMeta;
import io.skis.metadata.IdGeneration;
import io.skis.metadata.PropertyMeta;
import io.skis.mutation.InsertResult;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.OptimisticLockException;
import io.skis.query.QueryTable;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
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
    performInsert(table, value, context, false);
    return 1;
  }

  @Override
  public <E> InsertResult<E> insertResult(QueryTable<E> table, E value) {
    return new InsertResult<>(1, performInsert(table, value, ExecutionContext.EMPTY, true));
  }

  @Override
  public <E> InsertResult<E> insertResult(
      QueryTable<E> table, E value, ExecutionOptions executionOptions) {
    return new InsertResult<>(
        1,
        performInsert(
            table,
            value,
            ExecutionContext.of(Objects.requireNonNull(executionOptions, "executionOptions")),
            true));
  }

  private <E> E performInsert(
      QueryTable<E> table, E value, ExecutionContext context, boolean resultRequested) {
    OperationObservation observation = startObservation(ExecutionOperationKind.INSERT);
    try {
      EntityMeta<E> entity = requireEntity(table);
      EntityMutationPlanSet<E> plans = requirePlanSet(entity);
      requireEntityValue(entity, value);
      IdGeneration generation = plans.generation();
      if (generation != IdGeneration.ASSIGNED && plans.recordEntity() && !resultRequested) {
        throw idFailure(
            plans,
            SkisErrorCode.MUTATION_ID_VALUE_INVALID,
            "generated record primary keys require insertResult",
            null);
      }
      Object originalId = readId(plans, value);
      validateId(plans, originalId);
      E result = value;
      if (generation == IdGeneration.ASSIGNED) {
        int affected =
            jdbcExecutor.executeUpdate(
                plans.insert(), value, context, observation, StatementPurpose.PRIMARY);
        if (affected != 1) {
          throw unexpectedRowCount(entity, "insert", affected, "exactly one");
        }
      } else {
        Object effectiveId = originalId;
        if (effectiveId == null && generation != IdGeneration.IDENTITY) {
          effectiveId = generateId(plans);
        }
        InsertArguments<E> arguments = new InsertArguments<>(value, effectiveId);
        if (generation == IdGeneration.IDENTITY) {
          effectiveId =
              jdbcExecutor.executeGeneratedKey(
                  plans.generatedInsert(),
                  arguments,
                  plans.idProperty().column().name(),
                  plans.idCodec(),
                  context,
                  observation,
                  StatementPurpose.PRIMARY);
        } else {
          int affected =
              jdbcExecutor.executeUpdate(
                  plans.generatedInsert(),
                  arguments,
                  context,
                  observation,
                  StatementPurpose.PRIMARY);
          if (affected != 1) {
            throw unexpectedRowCount(entity, "insert", affected, "exactly one");
          }
        }
        if (originalId == null) {
          try {
            result = plans.idAccess().apply(value, Objects.requireNonNull(effectiveId));
            requireEntityValue(entity, result);
          } catch (SQLException | RuntimeException failure) {
            throw idFailure(
                plans,
                SkisErrorCode.MUTATION_ID_APPLY_FAILED,
                "primary-key application failed after SQL and resource completion",
                failure);
          }
        }
      }
      completeSucceeded(observation);
      return result;
    } catch (RuntimeException | Error failure) {
      completeFailed(observation, failure);
      throw failure;
    }
  }

  private static <E> @Nullable Object readId(EntityMutationPlanSet<E> plans, E value) {
    try {
      return plans.idAccess().read(value);
    } catch (SQLException | RuntimeException failure) {
      throw idFailure(
          plans,
          SkisErrorCode.MUTATION_ID_ACCESS_FAILED,
          "generated primary-key reader failed before connection acquisition",
          failure);
    }
  }

  private static void validateId(EntityMutationPlanSet<?> plans, @Nullable Object id) {
    if (id != null && !plans.idProperty().javaType().isInstance(id)) {
      throw idFailure(
          plans,
          SkisErrorCode.MUTATION_VALUE_TYPE_MISMATCH,
          "primary-key value does not match its declared Java type",
          null);
    }
    switch (plans.generation()) {
      case ASSIGNED -> {
        if (id == null) {
          throw idFailure(
              plans,
              SkisErrorCode.MUTATION_REQUIRED_VALUE_NULL,
              "assigned primary key is required",
              null);
        }
      }
      case IDENTITY -> {
        if (id != null) {
          throw idFailure(
              plans,
              SkisErrorCode.MUTATION_ID_VALUE_INVALID,
              "identity primary key must be absent before insert",
              null);
        }
      }
      case UUID -> {
        if (id instanceof String text && !canonicalUuid(text)) {
          throw idFailure(
              plans,
              SkisErrorCode.MUTATION_ID_VALUE_INVALID,
              "UUID text must have canonical lowercase hyphenated form",
              null);
        }
      }
      case SNOWFLAKE -> {
        if (id instanceof Long number && number <= 0) {
          throw idFailure(
              plans,
              SkisErrorCode.MUTATION_ID_VALUE_INVALID,
              "supplied snowflake primary key must be positive",
              null);
        }
      }
    }
  }

  private Object generateId(EntityMutationPlanSet<?> plans) {
    try {
      if (plans.generation() == IdGeneration.SNOWFLAKE) {
        return planCatalog.snowflakeIds().nextId();
      }
      UUID uuid = UUID.randomUUID();
      return plans.idProperty().javaType() == String.class ? uuid.toString() : uuid;
    } catch (RuntimeException failure) {
      throw idFailure(
          plans,
          SkisErrorCode.MUTATION_ID_GENERATION_FAILED,
          "application primary-key generation failed",
          failure);
    }
  }

  private static boolean canonicalUuid(String value) {
    if (value.length() != 36) {
      return false;
    }
    for (int index = 0; index < 36; index++) {
      char character = value.charAt(index);
      if (index == 8 || index == 13 || index == 18 || index == 23) {
        if (character != '-') {
          return false;
        }
      } else if (!((character >= '0' && character <= '9')
          || (character >= 'a' && character <= 'f'))) {
        return false;
      }
    }
    return true;
  }

  private static MutationException idFailure(
      EntityMutationPlanSet<?> plans,
      SkisErrorCode code,
      String reason,
      @Nullable Throwable cause) {
    var originalDiagnostics =
        cause instanceof MutationException failure && failure.code() == code
            ? failure.diagnostics()
            : null;
    if (originalDiagnostics != null) {
      reason =
          Objects.requireNonNull(Objects.requireNonNull(originalDiagnostics.facts()).detail());
    }
    String detail =
        "entity="
            + plans.entity().entityName()
            + ", property="
            + plans.idProperty().name()
            + ", strategy="
            + plans.generation()
            + ": "
            + reason;
    return originalDiagnostics == null
        ? MutationException.of(code, detail, cause)
        : MutationException.of(code, originalDiagnostics.withDetail(detail), cause);
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
