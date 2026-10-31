package io.skis.mutation.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.MutationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.SkisException;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.jdbc.internal.CompiledMutationPlan;
import io.skis.jdbc.internal.JdbcBatchOutcome;
import io.skis.jdbc.internal.JdbcExecutor;
import io.skis.mapping.spi.InsertArguments;
import io.skis.metadata.IdGeneration;
import io.skis.mutation.BatchInsertWrite;
import io.skis.mutation.BatchMode;
import io.skis.mutation.BatchOptions;
import io.skis.mutation.BatchResult;
import io.skis.mutation.BatchWriteException;
import io.skis.mutation.ConflictAction;
import io.skis.mutation.OptimisticLockException;
import io.skis.mutation.ReturningWrite;
import io.skis.mutation.Written;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.internal.MutationQuerySupport;
import io.skis.query.internal.WriteInputCompiler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Ordered, bounded collection execution; no transaction changes or automatic retries. */
final class BatchWrites {

  private BatchWrites() {}

  enum Kind {
    INSERT,
    UPDATE,
    DELETE
  }

  static <E> Input<E> input(
      DefaultMutationOperations operations, QueryTable<E> table, List<E> entities, Kind kind) {
    return new Input<>(operations, table, entities, kind, BatchOptions.DEFAULT);
  }

  static final class Input<E> extends WriteSelections<E> implements BatchInsertWrite<E> {

    final List<E> entities;
    final Kind kind;
    final BatchOptions options;
    private volatile ValuesBatches.@Nullable Planner<E, Object> countPlanner;

    Input(
        DefaultMutationOperations operations,
        QueryTable<E> table,
        List<E> entities,
        Kind kind,
        BatchOptions options) {
      super(operations, table);
      this.entities = entities;
      this.kind = kind;
      this.options = options;
    }

    ValuesBatches.Planner<E, Object> countPlanner() {
      var planner = countPlanner;
      if (planner != null) return planner;
      synchronized (this) {
        planner = countPlanner;
        if (planner == null) {
          planner = new ValuesBatches.Planner<>(this, null, null);
          countPlanner = planner;
        }
        return planner;
      }
    }

    @Override
    public BatchInsertWrite<E> batchMode(BatchMode mode) {
      return batchOptions(options.withMode(mode));
    }

    @Override
    public BatchInsertWrite<E> batchOptions(BatchOptions options) {
      return new Input<>(operations, table, entities, kind, Objects.requireNonNull(options));
    }

    @Override
    public ConflictAction<E> onConflict(Selectable<?> first, Selectable<?>... remaining) {
      if (kind != Kind.INSERT) throw unsupported("conflict actions require INSERT inputs");
      return StructuredWrites.batchConflict(this, first, remaining);
    }

    @Override
    public int write() {
      return write(ExecutionOptions.NONE);
    }

    @Override
    public int write(ExecutionOptions executionOptions) {
      return count(this, null, Objects.requireNonNull(executionOptions));
    }

    @Override
    <R extends @Nullable Object> ReturningWrite<R> outputs(MutationQuerySupport.Result<R> result) {
      return outputsFor(this, null, result);
    }

    @Override
    public String toString() {
      return "BatchWrite[inputs=" + entities.size() + ", values redacted]";
    }
  }

  static MutationException unsupported(String detail) {
    return MutationException.of(SkisErrorCode.MUTATION_UNSUPPORTED, detail);
  }

  /** Statically unsupported shapes fail before observation as plain errors, not batch facts. */
  private static <E> void rejectUnsupported(Input<E> input, boolean returning) {
    if (input.entities.isEmpty()) return;
    BatchMode mode = input.options.mode();
    if (input.kind != Kind.INSERT) {
      if (returning)
        throw unsupported(
            "entity byId batches do not provide RETURNING rows; use value(entity) per input");
      if (mode == BatchMode.VALUES)
        throw unsupported(
            "VALUES transport is supported for INSERT; entity byId batches require ADD_BATCH");
      return;
    }
    if (returning && mode != BatchMode.VALUES)
      throw unsupported(
          "JDBC addBatch does not provide a portable RETURNING row stream;"
              + " select BatchMode.VALUES explicitly");
    if (mode == BatchMode.VALUES
        && input.entities.size() > 1
        && input.operations.plans(input.table).insertStatement().values().isEmpty())
      throw unsupported("multiple DEFAULT VALUES rows require ADD_BATCH");
  }

  private static MutationException invalidCounts() {
    return MutationException.of(
        SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
        "batch did not produce a complete exact affected-row count");
  }

  private sealed interface RecordedChunk permits DetailedChunk, UniformChunk, AggregateChunk {
    BatchResult.Report report();
  }

  private record DetailedChunk(BatchResult.Report report) implements RecordedChunk {}

  /** Uniform successful driver counts need no retained array on the normal count path. */
  private record UniformChunk(int first, int length, int count, boolean optimistic)
      implements RecordedChunk {
    @Override
    public BatchResult.Report report() {
      int[] counts = new int[length];
      if (count != 0) Arrays.fill(counts, count);
      return new BatchResult.Report(
          first, length, length, true, -1, counts, false, false, 0, optimistic);
    }
  }

  private record AggregateChunk(int first, int length, int count, boolean optimistic)
      implements RecordedChunk {
    @Override
    public BatchResult.Report report() {
      return new BatchResult.Report(
          first,
          length,
          length,
          true,
          -1,
          new int[0],
          true,
          count >= 0,
          Math.max(count, 0),
          optimistic);
    }
  }

  static final class Ledger {
    final int size;
    private final List<RecordedChunk> reports = new ArrayList<>();
    int first, length, failedInput = -1;
    long total;
    boolean executing;

    Ledger(int size) {
      this.size = size;
    }

    void begin(int first, int length) {
      this.first = first;
      this.length = length;
      executing = false;
      failedInput = -1;
    }

    void aggregate(int count, boolean optimistic) {
      reports.add(new AggregateChunk(first, length, count, optimistic));
      length = 0;
    }

    void record(JdbcBatchOutcome outcome, boolean optimistic) {
      int[] counts = outcome.counts();
      if (outcome.failure() == null
          && outcome.executed()
          && outcome.submitted() == length
          && counts.length == length
          && length > 0
          && counts[0] >= 0
          && Arrays.mismatch(counts, 0, length - 1, counts, 1, length) < 0) {
        reports.add(new UniformChunk(first, length, counts[0], optimistic));
        length = 0;
        return;
      }
      reports.add(
          new DetailedChunk(
              new BatchResult.Report(
                  first,
                  length,
                  outcome.submitted(),
                  outcome.executed(),
                  outcome.failedInput(),
                  counts,
                  false,
                  false,
                  0,
                  optimistic)));
      length = 0;
    }

    BatchWriteException failure(RuntimeException failure) {
      if (length > 0) {
        reports.add(
            new DetailedChunk(
                new BatchResult.Report(
                    first,
                    length,
                    executing ? length : 0,
                    executing,
                    failedInput,
                    new int[0],
                    false,
                    false,
                    0)));
        length = 0;
      }
      SkisException cause =
          failure instanceof SkisException skis
              ? skis
              : MutationException.of(
                  SkisErrorCode.MUTATION_PARAMETER_SHAPE,
                  "batch input preparation failed",
                  failure);
      List<BatchResult.Report> detached = new ArrayList<>(reports.size());
      for (RecordedChunk report : reports) detached.add(report.report());
      return new BatchWriteException(BatchResult.fromReports(size, detached), cause);
    }
  }

  static <E> int count(
      Input<E> input,
      StructuredWrites.@Nullable Spec<E> conflict,
      ExecutionOptions executionOptions) {
    var operations = input.operations;
    operations.beforeWrite();
    rejectUnsupported(input, false);
    OperationObservation observation = operations.startObservation(operation(input.kind));
    Ledger ledger = new Ledger(input.entities.size());
    try {
      var plans = operations.plans(input.table);
      if (input.entities.isEmpty()) {
        DefaultMutationOperations.completeSucceeded(observation);
        return 0;
      }
      if (input.options.mode() == BatchMode.VALUES || conflict != null) {
        int count = ValuesBatches.count(input, conflict, executionOptions, observation, ledger);
        DefaultMutationOperations.completeSucceeded(observation);
        return count;
      }
      preflightShapes(input, plans, ledger);
      var first = chunk(input, plans, 0, ledger);
      ledger.begin(0, first.size());
      int result =
          operations.jdbcExecutor.executeBatchWork(
              first.plan(),
              ExecutionContext.of(executionOptions),
              observation,
              connection -> {
                Chunk<?> current = first;
                int offset = 0;
                while (true) {
                  ledger.begin(offset, current.size());
                  JdbcBatchOutcome outcome = execute(connection, current);
                  ledger.record(outcome, current.checked());
                  if (outcome.failure() != null) throw ledger.failure(outcome.failure());
                  int[] counts = outcome.counts();
                  if (counts.length != current.size()) throw ledger.failure(invalidCounts());
                  for (int count : counts) {
                    if (count < 0
                        || input.kind == Kind.INSERT && count != 1
                        || input.kind != Kind.INSERT && count > 1
                        || current.checked() && count == 0)
                      throw ledger.failure(
                          current.checked() && count == 0
                              ? OptimisticLockException.of("optimistic entity batch conflict")
                              : invalidCounts());
                    ledger.total += count;
                  }
                  if (ledger.total > Integer.MAX_VALUE) throw ledger.failure(invalidCounts());
                  offset += current.size();
                  if (offset == input.entities.size()) return (int) ledger.total;
                  ledger.begin(
                      offset, Math.min(input.options.chunkSize(), input.entities.size() - offset));
                  current = chunk(input, plans, offset, ledger);
                }
              });
      DefaultMutationOperations.completeSucceeded(observation);
      return result;
    } catch (RuntimeException failure) {
      RuntimeException completed =
          failure instanceof BatchWriteException ? failure : ledger.failure(failure);
      DefaultMutationOperations.completeFailed(observation, completed);
      throw completed;
    } catch (Error failure) {
      DefaultMutationOperations.completeFailed(observation, failure);
      throw failure;
    }
  }

  private record Chunk<P>(CompiledMutationPlan<P> plan, List<P> parameters, boolean checked) {
    int size() {
      return parameters.size();
    }
  }

  private static <P> JdbcBatchOutcome execute(
      JdbcExecutor.BatchConnection connection, Chunk<P> chunk) {
    return connection.addBatch(chunk.plan(), chunk.parameters());
  }

  private static <E> void preflightShapes(
      Input<E> input, EntityMutationPlanSet<E> plans, Ledger ledger) {
    if (input.kind == Kind.INSERT || plans.entity().version().isEmpty()) return;
    int limit =
        input.operations.dynamicPlans().batchParameterLimit(input.options.parameterBudget());
    boolean deleting = input.kind == Kind.DELETE;
    var unchecked = plans.entityPlan(deleting, false);
    var checked = plans.entityPlan(deleting, true);
    if (unchecked.parameterCount() <= limit
        && checked.parameterCount() <= limit
        && rowBytes(unchecked) <= input.options.memoryBudgetBytes()
        && rowBytes(checked) <= input.options.memoryBudgetBytes()) return;
    ledger.begin(0, input.entities.size());
    for (int index = 0; index < input.entities.size(); index++) {
      ledger.failedInput = index;
      E entity = input.entities.get(index);
      DefaultMutationOperations.requireEntityValue(plans.entity(), entity);
      var plan = plans.readExpectedVersion(entity) == null ? unchecked : checked;
      input
          .operations
          .dynamicPlans()
          .validateBatchParameters(plan.parameterCount(), input.options.parameterBudget());
      if (rowBytes(plan) > input.options.memoryBudgetBytes())
        throw unsupported("one batch row exceeds its framework buffer budget");
    }
    ledger.failedInput = -1;
    ledger.length = 0;
  }

  private static long rowBytes(CompiledMutationPlan<?> plan) {
    return rowBytes(plan.parameterCount());
  }

  /** Shared framework-buffer estimate for one queued row of the given parameter width. */
  static long rowBytes(int parameters) {
    return 64L + parameters * 32L;
  }

  private static <E> void validateInput(Input<E> input, EntityMutationPlanSet<E> plans, E entity) {
    if (input.kind != Kind.INSERT) EntityWrites.validateEntity(plans, entity);
    else {
      DefaultMutationOperations.requireEntityValue(plans.entity(), entity);
      if (plans.generation() == IdGeneration.ASSIGNED)
        DefaultMutationOperations.validateId(
            plans, DefaultMutationOperations.readId(plans, entity));
    }
  }

  private static <E> Chunk<?> chunk(
      Input<E> input, EntityMutationPlanSet<E> plans, int first, Ledger ledger) {
    ledger.begin(first, 1);
    ledger.failedInput = 0;
    E value = input.entities.get(first);
    validateInput(input, plans, value);
    boolean checked = input.kind != Kind.INSERT && plans.readExpectedVersion(value) != null;
    ledger.failedInput = -1;
    CompiledMutationPlan<?> plan =
        input.kind == Kind.INSERT
            ? plans.generation() == IdGeneration.ASSIGNED ? plans.insert() : plans.generatedInsert()
            : plans.entityPlan(input.kind == Kind.DELETE, checked);
    input
        .operations
        .dynamicPlans()
        .validateBatchParameters(plan.parameterCount(), input.options.parameterBudget());
    int rows =
        (int)
            Math.min(input.options.chunkSize(), input.options.memoryBudgetBytes() / rowBytes(plan));
    if (rows == 0) throw unsupported("one batch row exceeds its framework buffer budget");
    int end = first + Math.min(input.entities.size() - first, rows);
    ledger.begin(first, end - first);
    for (int index = first + 1; index < end; index++) {
      ledger.failedInput = index - first;
      E entity = input.entities.get(index);
      validateInput(input, plans, entity);
      boolean entityChecked =
          input.kind != Kind.INSERT && plans.readExpectedVersion(entity) != null;
      if (entityChecked != checked) {
        end = index;
        break;
      }
    }
    ledger.begin(first, end - first);
    if (input.kind != Kind.INSERT || plans.generation() == IdGeneration.ASSIGNED) {
      @SuppressWarnings("unchecked")
      CompiledMutationPlan<E> typed = (CompiledMutationPlan<E>) plan;
      return new Chunk<>(typed, input.entities.subList(first, end), checked);
    }
    List<InsertArguments<E>> values = new ArrayList<>(end - first);
    for (int index = first; index < end; index++) {
      ledger.failedInput = index - first;
      E entity = input.entities.get(index);
      Object id = DefaultMutationOperations.readId(plans, entity);
      DefaultMutationOperations.validateId(plans, id);
      if (id == null && plans.generation() != IdGeneration.IDENTITY)
        id = input.operations.generateId(plans);
      values.add(new InsertArguments<>(entity, id));
    }
    ledger.failedInput = -1;
    return new Chunk<>(plans.generatedInsert(), values, false);
  }

  static <E, R extends @Nullable Object> ReturningWrite<R> outputsFor(
      Input<E> input,
      StructuredWrites.@Nullable Spec<E> conflict,
      MutationQuerySupport.Result<R> result) {
    return new ReturningWrite<>() {
      private final ValuesBatches.Planner<E, R> planner =
          new ValuesBatches.Planner<>(input, conflict, result);

      @Override
      public Written<R> write() {
        return write(ExecutionOptions.NONE);
      }

      @Override
      public Written<R> write(ExecutionOptions options) {
        Objects.requireNonNull(options, "options");
        input.operations.beforeWrite();
        input.operations.requireWritable(input.table);
        rejectUnsupported(input, true);
        if (input.entities.isEmpty()) {
          OperationObservation observation =
              input.operations.startObservation(operation(input.kind));
          try {
            var compiler =
                new WriteInputCompiler(input.operations.dynamicPlans().registry(), input.table, 0);
            compiler.returning(result);
            compiler.finish();
            DefaultMutationOperations.completeSucceeded(observation);
            return new DefaultWritten<>(0, null);
          } catch (RuntimeException | Error failure) {
            DefaultMutationOperations.completeFailed(observation, failure);
            throw failure;
          }
        }
        return ValuesBatches.returning(planner, options);
      }
    };
  }

  private static ExecutionOperationKind operation(Kind kind) {
    return switch (kind) {
      case INSERT -> ExecutionOperationKind.INSERT;
      case UPDATE -> ExecutionOperationKind.UPDATE_BY_ID;
      case DELETE -> ExecutionOperationKind.DELETE_BY_ID;
    };
  }
}
