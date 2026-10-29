package io.skis.mutation.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.MutationException;
import io.skis.core.SkisDiagnosticStage;
import io.skis.core.SkisErrorCode;
import io.skis.core.TransactionException;
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
import io.skis.mutation.DeleteWrite;
import io.skis.mutation.InsertInto;
import io.skis.mutation.MergeInto;
import io.skis.mutation.MutationOperations;
import io.skis.mutation.OptimisticLockException;
import io.skis.mutation.UpdateSet;
import io.skis.mutation.Written;
import io.skis.query.QueryTable;
import io.skis.query.internal.MutationQuerySupport;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/** Default immutable entity mutation facade. */
final class DefaultMutationOperations implements MutationOperations {

  private final JdbcExecutor jdbcExecutor;
  private final MutationPlanCatalog planCatalog;
  private final ExecutionListenerDispatcher listenerDispatcher;
  private final String dialectId;
  private final @Nullable BooleanSupplier activeSession;

  DefaultMutationOperations(MutationPlanCatalog planCatalog, JdbcExecutor jdbcExecutor) {
    this(planCatalog, jdbcExecutor, null);
  }

  DefaultMutationOperations(
      MutationPlanCatalog planCatalog,
      JdbcExecutor jdbcExecutor,
      @Nullable BooleanSupplier activeSession) {
    this.planCatalog = Objects.requireNonNull(planCatalog, "planCatalog");
    this.jdbcExecutor = Objects.requireNonNull(jdbcExecutor, "jdbcExecutor");
    this.listenerDispatcher = planCatalog.listenerDispatcher();
    this.dialectId = planCatalog.dialectId();
    this.activeSession = activeSession;
  }

  @Override
  public <E> InsertInto<E> insertInto(QueryTable<E> table) {
    return MutationRequests.insert(this, table);
  }

  @Override
  public <E> MergeInto<E> mergeInto(QueryTable<E> table) {
    return StructuredWrites.merge(this, table);
  }

  <E> EntityMutationPlanSet<E> plans(QueryTable<E> table) {
    return requirePlanSet(requireEntity(table));
  }

  @Override
  public <E> UpdateSet<E> update(QueryTable<E> table) {
    return MutationRequests.update(this, table);
  }

  @Override
  public <E> DeleteWrite<E> deleteFrom(QueryTable<E> table) {
    return MutationRequests.delete(this, table);
  }

  DynamicMutationPlans dynamicPlans() {
    return planCatalog.dynamicPlans();
  }

  void requireWritable(QueryTable<?> table) {
    requirePlanSet(requireEntity(table));
  }

  private void beforeWrite() {
    ExecutionListenerDispatcher.rejectReentrantCall();
    BooleanSupplier active = activeSession;
    if (active != null && !active.getAsBoolean()) {
      throw TransactionException.of(
          SkisErrorCode.SESSION_INACTIVE, "SKIS session is no longer active");
    }
  }

  <E> int writeInsert(QueryTable<E> table, E value, ExecutionOptions options) {
    performInsert(table, value, ExecutionContext.of(options));
    return 1;
  }

  private <E> void performInsert(QueryTable<E> table, E value, ExecutionContext context) {
    beforeWrite();
    OperationObservation observation = startObservation(ExecutionOperationKind.INSERT);
    try {
      EntityMeta<E> entity = requireEntity(table);
      EntityMutationPlanSet<E> plans = requirePlanSet(entity);
      requireEntityValue(entity, value);
      IdGeneration generation = plans.generation();
      if (generation != IdGeneration.ASSIGNED && plans.recordEntity()) {
        throw idFailure(
            plans,
            SkisErrorCode.MUTATION_ID_VALUE_INVALID,
            "generated record primary keys require explicit returning() or returning(projection)",
            null);
      }
      if (generation == IdGeneration.IDENTITY && !dynamicPlans().supportsGeneratedKeys()) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_UNSUPPORTED,
            "count-only IDENTITY requires JDBC generated-key support; use explicit RETURNING");
      }
      Object originalId = readId(plans, value);
      validateId(plans, originalId);
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
            E result = plans.idAccess().apply(value, Objects.requireNonNull(effectiveId));
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
    } catch (RuntimeException | Error failure) {
      completeFailed(observation, failure);
      throw failure;
    }
  }

  <E, R> Written<R> writeInsertReturning(
      QueryTable<E> table,
      E value,
      ExecutionOptions options,
      MutationQuerySupport.Result<R> result,
      DynamicMutationPlans.InsertRequest<E, R> request) {
    beforeWrite();
    OperationObservation observation = startObservation(ExecutionOperationKind.INSERT);
    try {
      EntityMutationPlanSet<E> plans = requirePlanSet(requireEntity(table));
      requireEntityValue(plans.entity(), value);
      Object originalId = readId(plans, value);
      validateId(plans, originalId);
      IdGeneration generation = plans.generation();
      ExecutionContext context = ExecutionContext.of(options);
      DynamicMutationPlans.Returned<R> returned;
      if (generation == IdGeneration.ASSIGNED) {
        DynamicMutationPlans.Plan<R, E> plan =
            dynamicPlans()
                .insert(
                    request.assigned,
                    plans.insertStatement(),
                    plans,
                    plans.insert(),
                    result,
                    false,
                    observation);
        returned = executeInsertReturning(plan, value, context, observation);
      } else {
        boolean readKey = !plans.recordEntity();
        DynamicMutationPlans.Plan<R, InsertArguments<E>> plan =
            dynamicPlans()
                .insert(
                    request.generated,
                    plans.insertStatement(),
                    plans,
                    plans.generatedInsert(),
                    result,
                    readKey,
                    observation);
        Object effectiveId = originalId;
        if (effectiveId == null && generation != IdGeneration.IDENTITY) {
          effectiveId = generateId(plans);
        }
        returned =
            executeInsertReturning(
                plan, new InsertArguments<>(value, effectiveId), context, observation);
      }
      if (generation != IdGeneration.ASSIGNED
          && !plans.recordEntity()
          && !Objects.equals(originalId, returned.key())) {
        try {
          E applied = plans.idAccess().apply(value, Objects.requireNonNull(returned.key()));
          requireEntityValue(plans.entity(), applied);
        } catch (SQLException | RuntimeException failure) {
          throw idFailure(
              plans,
              SkisErrorCode.MUTATION_ID_APPLY_FAILED,
              "primary-key application failed after SQL and resource completion",
              failure);
        }
      }
      Written<R> completed = new DefaultWritten<>(1, returned.value());
      completeSucceeded(observation);
      return completed;
    } catch (RuntimeException | Error failure) {
      completeFailed(observation, failure);
      throw failure;
    }
  }

  private <R, P> DynamicMutationPlans.Returned<R> executeInsertReturning(
      DynamicMutationPlans.Plan<R, P> plan,
      P parameters,
      ExecutionContext context,
      @Nullable OperationObservation observation) {
    return jdbcExecutor.executeReturningOne(
        plan.statement(),
        plan.decoder(),
        plan.columnCount(),
        parameters,
        context,
        observation,
        StatementPurpose.PRIMARY);
  }

  <E> int writeEmptyBatch(QueryTable<E> table, List<E> entities, ExecutionOptions options) {
    Objects.requireNonNull(options, "options");
    beforeWrite();
    OperationObservation observation = startObservation(ExecutionOperationKind.INSERT);
    try {
      requireWritable(table);
      if (!entities.isEmpty()) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_UNSUPPORTED,
            "nonempty collection insertion requires the batch execution capability");
      }
      completeSucceeded(observation);
      return 0;
    } catch (RuntimeException | Error failure) {
      completeFailed(observation, failure);
      throw failure;
    }
  }

  int writeConditional(MutationRequests.Conditional<?> request, ExecutionOptions options) {
    beforeWrite();
    OperationObservation observation =
        startObservation(
            request.update ? ExecutionOperationKind.UPDATE : ExecutionOperationKind.DELETE);
    try {
      MutationRequests.Prepared prepared = request.prepare();
      var plan =
          dynamicPlans()
              .count(
                  request.countPlan,
                  request.update ? "update" : "delete",
                  prepared.statement(),
                  prepared.inputs(),
                  observation);
      int rows =
          jdbcExecutor.executeUpdate(
              plan,
              prepared.inputs().values(),
              ExecutionContext.of(options),
              observation,
              StatementPurpose.PRIMARY);
      if (rows < 0) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
            "conditional write did not report an exact affected-row count");
      }
      completeSucceeded(observation);
      return rows;
    } catch (RuntimeException | Error failure) {
      completeFailed(observation, failure);
      throw failure;
    }
  }

  @SuppressWarnings("Convert2Diamond")
  <R> Written<List<R>> writeConditionalReturning(
      MutationRequests.Conditional<?> request,
      ExecutionOptions options,
      MutationQuerySupport.Result<R> result,
      DynamicMutationPlans.Request<DynamicMutationPlans.ConditionalPlan<R>> requestPlan) {
    beforeWrite();
    OperationObservation observation =
        startObservation(
            request.update ? ExecutionOperationKind.UPDATE : ExecutionOperationKind.DELETE);
    try {
      MutationRequests.Prepared prepared = request.prepare();
      var plan =
          dynamicPlans()
              .conditional(
                  requestPlan,
                  request.update ? "update" : "delete",
                  prepared.statement(),
                  prepared.inputs(),
                  result,
                  observation);
      List<R> rows =
          jdbcExecutor.executeReturning(
              plan.statement(),
              plan.decoder(),
              plan.columnCount(),
              prepared.inputs().values(),
              ExecutionContext.of(options),
              observation,
              StatementPurpose.PRIMARY);
      Written<List<R>> completed =
          new DefaultWritten<List<R>>(rows.size(), rows.isEmpty() ? null : rows);
      completeSucceeded(observation);
      return completed;
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

  <E> int writeStructuredCount(StructuredWrites.Request<E> request, ExecutionOptions options) {
    beforeWrite();
    OperationObservation observation =
        startObservation(
            request.spec.merge ? ExecutionOperationKind.MERGE : ExecutionOperationKind.INSERT);
    try {
      Object expected = request.expectedValue();
      var prepared = request.prepare(expected != null);
      boolean readKey = needsStructuredKey(prepared);
      var plan =
          dynamicPlans()
              .structured(request.count(prepared.checked()), prepared, null, readKey, observation);
      var arguments = structuredArguments(request, prepared, expected, false);
      int rows;
      if (readKey) {
        var row =
            jdbcExecutor.executeReturningOptional(
                plan.statement(),
                Objects.requireNonNull(plan.decoder()),
                plan.columnCount(),
                arguments,
                ExecutionContext.of(options),
                observation,
                StatementPurpose.PRIMARY);
        rows = row == null ? 0 : 1;
        validateStructuredCount(request, prepared, rows);
        if (row != null) applyStructuredKey(request, prepared, arguments, row.key());
      } else {
        rows =
            jdbcExecutor.executeUpdate(
                plan.statement(),
                arguments,
                ExecutionContext.of(options),
                observation,
                StatementPurpose.PRIMARY);
        validateStructuredCount(request, prepared, rows);
      }
      completeSucceeded(observation);
      return rows;
    } catch (RuntimeException | Error failure) {
      completeFailed(observation, failure);
      throw failure;
    }
  }

  @SuppressWarnings("Convert2Diamond")
  <E, R> Written<R> writeStructuredOne(
      StructuredWrites.Request<E> request,
      MutationQuerySupport.Result<R> result,
      StructuredWrites.Choices<R> choices,
      ExecutionOptions options) {
    beforeWrite();
    OperationObservation observation =
        startObservation(
            request.spec.merge ? ExecutionOperationKind.MERGE : ExecutionOperationKind.INSERT);
    try {
      Object expected = request.expectedValue();
      var prepared = request.prepare(expected != null);
      boolean readKey = needsStructuredKey(prepared);
      var plan =
          dynamicPlans()
              .structured(choices.one(prepared.checked()), prepared, result, readKey, observation);
      var arguments = structuredArguments(request, prepared, expected, true);
      var row =
          jdbcExecutor.executeReturningOptional(
              plan.statement(),
              Objects.requireNonNull(plan.decoder()),
              plan.columnCount(),
              arguments,
              ExecutionContext.of(options),
              observation,
              StatementPurpose.PRIMARY);
      int count = row == null ? 0 : 1;
      validateStructuredCount(request, prepared, count);
      if (row != null && readKey) applyStructuredKey(request, prepared, arguments, row.key());
      Written<R> completed = new DefaultWritten<R>(count, row == null ? null : row.value());
      completeSucceeded(observation);
      return completed;
    } catch (RuntimeException | Error failure) {
      completeFailed(observation, failure);
      throw failure;
    }
  }

  @SuppressWarnings("Convert2Diamond")
  <E, R> Written<List<R>> writeStructuredMany(
      StructuredWrites.Request<E> request,
      MutationQuerySupport.Result<R> result,
      StructuredWrites.Choices<R> choices,
      ExecutionOptions options) {
    beforeWrite();
    OperationObservation observation = startObservation(ExecutionOperationKind.MERGE);
    try {
      Object expected = request.expectedValue();
      var prepared = request.prepare(expected != null);
      var arguments = structuredArguments(request, prepared, expected, true);
      var plan =
          dynamicPlans()
              .conditional(
                  choices.many(prepared.checked()),
                  prepared.resolved(),
                  prepared.inputs(),
                  result,
                  observation);
      List<R> rows =
          jdbcExecutor.executeReturning(
              plan.statement(),
              plan.decoder(),
              plan.columnCount(),
              arguments.values(),
              ExecutionContext.of(options),
              observation,
              StatementPurpose.PRIMARY);
      validateStructuredCount(request, prepared, rows.size());
      Written<List<R>> completed =
          new DefaultWritten<List<R>>(rows.size(), rows.isEmpty() ? null : rows);
      completeSucceeded(observation);
      return completed;
    } catch (RuntimeException | Error failure) {
      completeFailed(observation, failure);
      throw failure;
    }
  }

  private static boolean needsStructuredKey(StructuredWrites.Prepared prepared) {
    var plans = prepared.entityPlans();
    return plans != null && plans.generation() != IdGeneration.ASSIGNED && !plans.recordEntity();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private <E> StructuredWrites.Arguments structuredArguments(
      StructuredWrites.Request<E> request,
      StructuredWrites.Prepared prepared,
      @Nullable Object expected,
      boolean returning) {
    var spec = request.spec;
    Object base = spec.entity;
    Object originalId = null;
    EntityMutationPlanSet plans = prepared.entityPlans();
    boolean changed = false;
    @Nullable Object[] values = prepared.inputs().values();
    if (spec.entity != null && plans != null) {
      requireEntityValue(plans.entity(), spec.entity);
      Object original = readId(plans, spec.entity);
      originalId = original;
      validateId(plans, original);
      if (!returning && plans.recordEntity() && plans.generation() != IdGeneration.ASSIGNED)
        throw StructuredWrites.invalid("generated records require explicit RETURNING");
      if (plans.generation() != IdGeneration.ASSIGNED) {
        Object effective = original;
        if (effective == null && plans.generation() != IdGeneration.IDENTITY)
          effective = generateId(plans);
        base = new InsertArguments<>(spec.entity, effective);
        changed = true;
      }
    }
    if (prepared.checked() && prepared.expectedIndex() >= 0 && spec.expected == null) {
      values = values.clone();
      values[prepared.expectedIndex()] = Objects.requireNonNull(expected);
      changed = true;
    }
    return changed
        ? new StructuredWrites.Arguments(base, values, originalId)
        : prepared.fixedArguments();
  }

  private static void validateStructuredCount(
      StructuredWrites.Request<?> request, StructuredWrites.Prepared prepared, int rows) {
    if (rows < 0
        || prepared.single() && rows > 1
        || !request.spec.conflict && !request.spec.merge && rows != 1)
      throw MutationException.of(
          SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
          "structured write returned an invalid affected-row count");
    if (prepared.checked() && rows == 0)
      throw OptimisticLockException.of("optimistic structured write conflict");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void applyStructuredKey(
      StructuredWrites.Request<?> request,
      StructuredWrites.Prepared prepared,
      StructuredWrites.Arguments arguments,
      @Nullable Object key) {
    EntityMutationPlanSet plans = Objects.requireNonNull(prepared.entityPlans());
    Object entity = Objects.requireNonNull(request.spec.entity);
    try {
      if (!Objects.equals(arguments.originalId(), key)) {
        Object applied = plans.idAccess().apply(entity, Objects.requireNonNull(key));
        requireEntityValue(plans.entity(), applied);
      }
    } catch (SQLException | RuntimeException failure) {
      throw idFailure(
          plans,
          SkisErrorCode.MUTATION_ID_APPLY_FAILED,
          "actual primary-key application failed after SQL and resource completion",
          failure);
    }
  }

  static void validateId(EntityMutationPlanSet<?> plans, @Nullable Object id) {
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
      reason = Objects.requireNonNull(Objects.requireNonNull(originalDiagnostics.facts()).detail());
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
    beforeWrite();
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
    beforeWrite();
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
