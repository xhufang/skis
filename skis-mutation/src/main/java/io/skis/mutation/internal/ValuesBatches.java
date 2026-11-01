package io.skis.mutation.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.MutationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.internal.OperationObservation;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.mapping.spi.InsertArguments;
import io.skis.mapping.spi.JdbcWriteContext;
import io.skis.mapping.spi.ParameterBinder;
import io.skis.mapping.spi.ParameterBindingObserver;
import io.skis.metadata.IdGeneration;
import io.skis.mutation.BatchMode;
import io.skis.mutation.BatchOptions;
import io.skis.mutation.BatchWriteException;
import io.skis.mutation.OptimisticLockException;
import io.skis.mutation.Written;
import io.skis.query.QueryTable;
import io.skis.query.internal.MutationQuerySupport;
import io.skis.query.internal.WriteInputCompiler;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.ConflictInsertStatement;
import io.skis.sql.ast.ExcludedExpression;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.StatementAst;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Explicit native VALUES SQL and parameterized conflict batches, sharing the bounded plan cache.
 */
final class ValuesBatches {
  private ValuesBatches() {}

  private record Shape(int rows, boolean checked) {}

  private record Entry<R extends @Nullable Object>(
      StructuredWrites.Prepared prepared,
      DynamicMutationPlans.Request<DynamicMutationPlans.StructuredPlan<R>> request) {}

  static final class Planner<E, R extends @Nullable Object> {
    final BatchWrites.Input<E> input;
    final StructuredWrites.@Nullable Spec<E> conflict;
    final MutationQuerySupport.@Nullable Result<R> result;
    private final @Nullable String strictConflictViolation;
    private final Map<Shape, Entry<R>> entries =
        new LinkedHashMap<>(16, 0.75f, true) {
          // Full chunks plus the bounded partial-row buckets of one write.
          @Override
          protected boolean removeEldestEntry(Map.Entry<Shape, ValuesBatches.Entry<R>> eldest) {
            return size() > 8;
          }
        };

    Planner(
        BatchWrites.Input<E> input,
        StructuredWrites.@Nullable Spec<E> conflict,
        MutationQuerySupport.@Nullable Result<R> result) {
      this.input = input;
      this.conflict = conflict;
      this.result = result;
      this.strictConflictViolation =
          conflict == null || conflict.nothing
              ? null
              : StructuredWrites.strictConflictViolation(conflict);
    }

    /** Only invalid strict shapes need a scan; valid shapes retain chunk-local preparation. */
    void preflightStrictConflict(EntityMutationPlanSet<E> plans) {
      String violation = strictConflictViolation;
      if (violation == null) return;
      var conflict = Objects.requireNonNull(this.conflict);
      if (conflict.expected != null || conflict.expectedExpression != null)
        throw StructuredWrites.invalid(violation);
      if (plans.entity().version().isEmpty()) return;
      for (E entity : input.entities) {
        DefaultMutationOperations.requireEntityValue(plans.entity(), entity);
        if (plans.readExpectedVersion(entity) != null) throw StructuredWrites.invalid(violation);
      }
    }

    boolean checking(E entity, EntityMutationPlanSet<E> plans) {
      DefaultMutationOperations.requireEntityValue(plans.entity(), entity);
      if (conflict == null || conflict.nothing) return false;
      boolean checked =
          conflict.expected != null
              || conflict.expectedExpression != null
              || plans.readExpectedVersion(entity) != null;
      if (checked && strictConflictViolation != null)
        throw StructuredWrites.invalid(strictConflictViolation);
      return checked;
    }

    int width(EntityMutationPlanSet<E> plans) {
      return plans.insertStatement().values().size();
    }

    synchronized Entry<R> entry(int rows, boolean checked, EntityMutationPlanSet<E> plans) {
      Shape shape = new Shape(rows, checked);
      Entry<R> known = entries.get(shape);
      if (known != null) return known;
      int width = width(plans), prefixCount = Math.multiplyExact(rows, width);
      if (rows > 1 && width == 0)
        throw BatchWrites.unsupported(
            "multiple DEFAULT VALUES rows have no supported native VALUES grammar");
      WriteInputCompiler compiler =
          new WriteInputCompiler(
              input.operations.dynamicPlans().registry(), input.table, prefixCount);
      List<ColumnExpression<?, ?>> columns = new ArrayList<>(width);
      for (var column : plans.insertStatement().columns())
        columns.add(compiler.column(column.property()));
      List<List<SqlExpression<?>>> rowValues = new ArrayList<>(rows);
      for (int row = 0; row < rows; row++) {
        List<SqlExpression<?>> values = new ArrayList<>(width);
        for (int index = 0; index < width; index++)
          values.add(slot(row * width + index, columns.get(index)));
        rowValues.add(List.copyOf(values));
      }
      var insert =
          new InsertStatement(
              MutationQuerySupport.table(input.table),
              columns,
              rowValues.getFirst(),
              rowValues.subList(1, rows),
              null);
      StatementAst statement = insert;
      if (conflict != null) {
        var assignments = compiler.assignments(conflict.assignments, !conflict.nothing);
        SqlPredicate condition = compiler.condition(conflict.filter);
        if (checked && !conflict.nothing) {
          SqlPredicate expected;
          if (conflict.expected != null || conflict.expectedExpression != null)
            expected = compiler.expectedVersion(conflict.expected, conflict.expectedExpression);
          else expected = excludedVersion(compiler, input.table);
          condition = LogicalPredicate.and(List.of(condition, expected));
        }
        statement =
            new ConflictInsertStatement(
                insert, compiler.columns(conflict.keys), assignments, condition, conflict.nothing);
      }
      compiler.returning(result);
      var inputs = compiler.finish();
      ParameterBinder<?> binder =
          plans.generation() == IdGeneration.ASSIGNED
              ? plans.insert().parameterBinder()
              : plans.generatedInsert().parameterBinder();
      @SuppressWarnings("unchecked")
      ParameterBinder<Object> typed = (ParameterBinder<Object>) binder;
      // One row binds the entity input directly, matching single-row structured writes; only
      // multi-row statements (whose SQL differs) consume an ordered input list.
      var prepared =
          new StructuredWrites.Prepared(
              ResolvedStatement.analyze(statement),
              inputs,
              rows == 1 ? typed : new RowsBinder(typed, width),
              prefixCount,
              plans.structuredIdentity(),
              plans,
              compiler.column(plans.idProperty()),
              checked,
              -1,
              new StructuredWrites.Arguments(null, inputs.values()),
              "insert",
              input.options.parameterBudget());
      known = new Entry<>(prepared, new DynamicMutationPlans.Request<>());
      entries.put(shape, known);
      return known;
    }

    DynamicMutationPlans.StructuredPlan<R> plan(
        Entry<R> entry, @Nullable OperationObservation observation) {
      var plan =
          input
              .operations
              .dynamicPlans()
              .structured(entry.request(), entry.prepared(), result, false, observation);
      input
          .operations
          .dynamicPlans()
          .validateBatchParameters(
              plan.statement().parameterCount(), input.options.parameterBudget());
      return plan;
    }

    int maximum(
        EntityMutationPlanSet<E> plans,
        boolean checked,
        @Nullable OperationObservation observation) {
      // BatchWrites rejects byId VALUES and multi-row DEFAULT VALUES before observation.
      int width = width(plans);
      var single = plan(entry(1, checked, plans), observation);
      return maximumRows(width, single.statement().parameterCount());
    }

    private int maximumRows(int width, int parameterCount) {
      int budget =
          input.operations.dynamicPlans().batchParameterLimit(input.options.parameterBudget());
      int fixed = parameterCount - width;
      int parameterRows =
          input.options.mode() == BatchMode.ADD_BATCH
              ? BatchOptions.MAX_CHUNK_SIZE
              : width == 0 ? 1 : (budget - fixed) / width;
      long bytesPerRow = BatchWrites.rowBytes(width);
      int memoryRows =
          (int)
              Math.min(
                  BatchOptions.MAX_CHUNK_SIZE, input.options.memoryBudgetBytes() / bytesPerRow);
      int maximum = Math.min(input.options.chunkSize(), Math.min(parameterRows, memoryRows));
      if (maximum < 1)
        throw BatchWrites.unsupported(
            "one VALUES row exceeds the final parameter or framework buffer budget");
      return maximum;
    }

    int end(
        int first,
        int maximum,
        boolean checked,
        EntityMutationPlanSet<E> plans,
        BatchWrites.Ledger ledger) {
      int end = first + Math.min(maximum, input.entities.size() - first);
      ledger.begin(first, end - first);
      for (int index = first; index < end; index++) {
        ledger.failedInput = index - first;
        E entity = input.entities.get(index);
        if (checking(entity, plans) != checked) {
          end = index;
          break;
        }
        DefaultMutationOperations.validateId(
            plans, DefaultMutationOperations.readId(plans, entity));
      }
      if (input.options.mode() == BatchMode.VALUES) end = first + rowBucket(end - first, maximum);
      ledger.begin(first, end - first);
      return end;
    }

    /**
     * Native VALUES SQL text depends on its row count, so partial chunks use a few fixed sizes
     * (full, multiples of 16, then powers of two) instead of one shared plan per remainder.
     */
    private static int rowBucket(int rows, int maximum) {
      if (rows >= maximum) return maximum;
      if (rows >= 16) return rows & ~15;
      return Integer.highestOneBit(rows);
    }

    List<? extends Object> arguments(
        int first, int end, EntityMutationPlanSet<E> plans, BatchWrites.Ledger ledger) {
      if (plans.generation() == IdGeneration.ASSIGNED) return input.entities.subList(first, end);
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
      return values;
    }

    void validateCount(int count, int inputs, boolean checked) {
      if (count < 0 || count > inputs || conflict == null && count != inputs)
        throw MutationException.of(
            SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
            "native VALUES affected-row count differs from its input contract");
      if (checked && count != inputs)
        throw OptimisticLockException.of(
            "optimistic conflict in native VALUES batch; input correspondence is unknown");
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static ParameterSlot<?> slot(int ordinal, ColumnExpression<?, ?> column) {
    return new ParameterSlot(ordinal, column.javaType(), column.nullable());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static SqlPredicate excludedVersion(WriteInputCompiler compiler, QueryTable<?> table) {
    ColumnExpression column = compiler.column(table.entity().version().orElseThrow().property());
    return new ComparisonPredicate(
        column, ComparisonOperator.EQUAL, new ExcludedExpression(column));
  }

  private record RowsBinder(ParameterBinder<Object> row, int width)
      implements ParameterBinder<List<? extends Object>> {
    @Override
    public int bind(
        PreparedStatement statement,
        int first,
        List<? extends Object> inputs,
        JdbcWriteContext context)
        throws SQLException {
      int next = first;
      for (Object input : inputs) {
        int actual = row.bind(statement, next, input, context);
        if (actual != next + width)
          throw new SQLException("generated row binder changed its declared width");
        next = actual;
      }
      return next;
    }

    @Override
    public int bindObserved(
        PreparedStatement statement,
        int first,
        List<? extends Object> inputs,
        JdbcWriteContext context,
        ParameterBindingObserver observer)
        throws SQLException {
      int next = first;
      for (Object input : inputs) {
        int actual = row.bindObserved(statement, next, input, context, observer);
        if (actual != next + width)
          throw new SQLException("generated row binder changed its declared width");
        next = actual;
      }
      return next;
    }
  }

  /** One-row plans bind the entity input itself; multi-row plans bind the ordered list. */
  private static Object rowsBase(List<? extends Object> arguments) {
    return arguments.size() == 1 ? arguments.getFirst() : arguments;
  }

  private static <E> Planner<E, Object> countPlanner(
      BatchWrites.Input<E> input, StructuredWrites.@Nullable Spec<E> conflict) {
    return conflict == null ? input.countPlanner() : conflict.batchCountPlanner(input);
  }

  static <E> int count(
      BatchWrites.Input<E> input,
      StructuredWrites.@Nullable Spec<E> conflict,
      ExecutionOptions options,
      @Nullable OperationObservation observation,
      BatchWrites.Ledger ledger) {
    Planner<E, Object> planner = countPlanner(input, conflict);
    var plans = input.operations.plans(input.table);
    planner.preflightStrictConflict(plans);
    boolean checked = planner.checking(input.entities.getFirst(), plans);
    int maximum = planner.maximum(plans, checked, observation);
    int end = planner.end(0, maximum, checked, plans, ledger);
    var first =
        planner.plan(
            planner.entry(input.options.mode() == BatchMode.ADD_BATCH ? 1 : end, checked, plans),
            observation);
    var firstArguments = planner.arguments(0, end, plans, ledger);
    return input.operations.jdbcExecutor.executeBatchWork(
        first.statement(),
        ExecutionContext.of(options),
        observation,
        connection -> {
          int offset = 0;
          while (offset < input.entities.size()) {
            boolean checking = planner.checking(input.entities.get(offset), plans);
            int next =
                offset == 0
                    ? end
                    : planner.end(
                        offset,
                        planner.maximum(plans, checking, observation),
                        checking,
                        plans,
                        ledger);
            ledger.begin(offset, next - offset);
            List<? extends Object> arguments =
                offset == 0 ? firstArguments : planner.arguments(offset, next, plans, ledger);
            if (input.options.mode() == BatchMode.ADD_BATCH) {
              Entry<Object> entry = planner.entry(1, checking, plans);
              var plan = planner.plan(entry, observation);
              var values = entry.prepared().inputs().values();
              List<StructuredWrites.Arguments> queued = new ArrayList<>(arguments.size());
              for (Object argument : arguments)
                queued.add(new StructuredWrites.Arguments(argument, values));
              var outcome = connection.addBatch(plan.statement(), queued);
              ledger.record(outcome, checking);
              if (outcome.failure() != null) throw ledger.failure(outcome.failure());
              if (outcome.counts().length != queued.size())
                throw ledger.failure(
                    MutationException.of(
                        SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
                        "driver omitted batch counts"));
              for (int count : outcome.counts()) {
                planner.validateCount(count, 1, checking);
                ledger.total += count;
              }
            } else {
              Entry<Object> entry = planner.entry(next - offset, checking, plans);
              var plan = planner.plan(entry, observation);
              var statementArguments =
                  new StructuredWrites.Arguments(
                      rowsBase(arguments), entry.prepared().inputs().values());
              int count;
              try {
                count = connection.update(plan.statement(), statementArguments);
              } catch (RuntimeException failure) {
                ledger.executing = connection.executionStarted();
                throw failure;
              }
              ledger.aggregate(count, checking);
              planner.validateCount(count, next - offset, checking);
              ledger.total += count;
            }
            if (ledger.total > Integer.MAX_VALUE)
              throw ledger.failure(
                  MutationException.of(
                      SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
                      "exact batch count exceeds int"));
            offset = next;
          }
          return (int) ledger.total;
        });
  }

  static <E, R extends @Nullable Object> Written<R> returning(
      Planner<E, R> planner, ExecutionOptions options) {
    var input = planner.input;
    var operations = input.operations;
    OperationObservation observation = operations.startObservation(ExecutionOperationKind.INSERT);
    BatchWrites.Ledger ledger = new BatchWrites.Ledger(input.entities.size());
    List<R> rows = new ArrayList<>();
    try {
      operations.jdbcExecutor.validateReturningOptions(ExecutionContext.of(options));
      var plans = operations.plans(input.table);
      planner.preflightStrictConflict(plans);
      boolean checked = planner.checking(input.entities.getFirst(), plans);
      int end =
          planner.end(0, planner.maximum(plans, checked, observation), checked, plans, ledger);
      var first = planner.plan(planner.entry(end, checked, plans), observation);
      var firstArguments = planner.arguments(0, end, plans, ledger);
      Written<R> written =
          operations.jdbcExecutor.executeBatchWork(
              first.statement(),
              ExecutionContext.of(options),
              observation,
              connection -> {
                int offset = 0;
                while (offset < input.entities.size()) {
                  boolean checking = planner.checking(input.entities.get(offset), plans);
                  int next =
                      offset == 0
                          ? end
                          : planner.end(
                              offset,
                              planner.maximum(plans, checking, observation),
                              checking,
                              plans,
                              ledger);
                  ledger.begin(offset, next - offset);
                  Entry<R> entry = planner.entry(next - offset, checking, plans);
                  var plan = planner.plan(entry, observation);
                  var arguments =
                      new StructuredWrites.Arguments(
                          rowsBase(
                              offset == 0
                                  ? firstArguments
                                  : planner.arguments(offset, next, plans, ledger)),
                          entry.prepared().inputs().values());
                  List<R> returned;
                  try {
                    returned =
                        connection.returning(
                            plan.statement(),
                            Objects.requireNonNull(plan.outputDecoder()),
                            plan.columnCount(),
                            arguments);
                  } catch (RuntimeException failure) {
                    ledger.executing = connection.executionStarted();
                    throw failure;
                  }
                  rows.addAll(returned);
                  ledger.aggregate(returned.size(), checking);
                  planner.validateCount(returned.size(), next - offset, checking);
                  ledger.total += returned.size();
                  if (ledger.total > Integer.MAX_VALUE)
                    throw ledger.failure(
                        MutationException.of(
                            SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
                            "exact batch count exceeds int"));
                  offset = next;
                }
                return DefaultWritten.ownedRows(
                    (int) ledger.total, Collections.unmodifiableList(rows));
              });
      DefaultMutationOperations.completeSucceeded(observation);
      return written;
    } catch (RuntimeException failure) {
      BatchWriteException partial =
          failure instanceof BatchWriteException batch ? batch : ledger.failure(failure);
      RuntimeException completed =
          rows.isEmpty()
              ? partial
              : new BatchWriteException(partial.result().withRows(rows), partial);
      DefaultMutationOperations.completeFailed(observation, completed);
      throw completed;
    } catch (Error failure) {
      DefaultMutationOperations.completeFailed(observation, failure);
      throw failure;
    }
  }
}
