package io.skis.mutation.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.MutationException;
import io.skis.core.SkisErrorCode;
import io.skis.jdbc.internal.CompiledMutationPlan;
import io.skis.mutation.BatchInsertWrite;
import io.skis.mutation.ConflictAction;
import io.skis.mutation.DeleteWrite;
import io.skis.mutation.InsertInto;
import io.skis.mutation.InsertWrite;
import io.skis.mutation.ReturningWrite;
import io.skis.mutation.UnsafeMutationException;
import io.skis.mutation.UpdateSet;
import io.skis.mutation.UpdateWrite;
import io.skis.mutation.Written;
import io.skis.query.NonNullSelectable;
import io.skis.query.ProjectionSelection;
import io.skis.query.ProjectionType;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.internal.MutationQuerySupport;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.UpdateStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Value-bearing immutable request stages; none of these objects enter shared plan caches. */
final class MutationRequests {
  private MutationRequests() {}

  static <E> InsertInto<E> insert(DefaultMutationOperations operations, QueryTable<E> table) {
    return new Insert<>(operations, Objects.requireNonNull(table, "table"));
  }

  record InsertOwner<E>(DefaultMutationOperations operations, QueryTable<E> table) {}

  static <E> InsertOwner<E> insertOwner(InsertInto<E> stage) {
    if (!(stage instanceof Insert<E>(DefaultMutationOperations operations, QueryTable<E> table)))
      throw StructuredWrites.invalid("INSERT stage was not created by SKIS");
    return new InsertOwner<>(operations, table);
  }

  static <E> UpdateSet<E> update(DefaultMutationOperations operations, QueryTable<E> table) {
    return new Update<>(operations, table, List.of(), null, false);
  }

  static <E> DeleteWrite<E> delete(DefaultMutationOperations operations, QueryTable<E> table) {
    return new Delete<>(operations, table, null, false);
  }

  private record Returning<R>(Function<ExecutionOptions, Written<R>> execute)
      implements ReturningWrite<R> {
    @Override
    public Written<R> write() {
      return write(ExecutionOptions.NONE);
    }

    @Override
    public Written<R> write(ExecutionOptions options) {
      return execute.apply(Objects.requireNonNull(options, "options"));
    }

    @Override
    public String toString() {
      return "ReturningWrite[values redacted]";
    }
  }

  private record Insert<E>(DefaultMutationOperations operations, QueryTable<E> table)
      implements InsertInto<E> {
    @Override
    public InsertWrite<E> value(E entity) {
      return new Single<>(operations, table, Objects.requireNonNull(entity, "entity"));
    }

    @Override
    public BatchInsertWrite<E> values(Collection<? extends E> entities) {
      return new Batch<>(
          operations, table, List.copyOf(Objects.requireNonNull(entities, "entities")));
    }

    @Override
    public String toString() {
      return "InsertInto[values redacted]";
    }
  }

  private record Single<E>(DefaultMutationOperations operations, QueryTable<E> table, E entity)
      implements InsertWrite<E> {
    @Override
    public ConflictAction<E> onConflict(Selectable<?> first, Selectable<?>... remaining) {
      return StructuredWrites.conflict(operations, table, entity, first, remaining);
    }

    @Override
    public int write() {
      return write(ExecutionOptions.NONE);
    }

    @Override
    public int write(ExecutionOptions options) {
      return operations.writeInsert(table, entity, Objects.requireNonNull(options, "options"));
    }

    @Override
    public ReturningWrite<E> returning() {
      return returning(MutationQuerySupport.result(table));
    }

    @Override
    public <R> ReturningWrite<R> returning(ProjectionSelection<R> projection) {
      return returning(
          MutationQuerySupport.result(table, Objects.requireNonNull(projection, "projection")));
    }

    @Override
    public <R> ReturningWrite<R> returning(ProjectionType<R> projection) {
      return returning(
          MutationQuerySupport.result(table, Objects.requireNonNull(projection, "projection")));
    }

    private <R> ReturningWrite<R> returning(MutationQuerySupport.Result<R> result) {
      var request = new DynamicMutationPlans.InsertRequest<E, R>();
      return new Returning<>(
          options -> operations.writeInsertReturning(table, entity, options, result, request));
    }

    @Override
    public String toString() {
      return "InsertWrite[values redacted]";
    }
  }

  private record Batch<E>(
      DefaultMutationOperations operations, QueryTable<E> table, List<E> entities)
      implements BatchInsertWrite<E> {
    @Override
    public int write() {
      return write(ExecutionOptions.NONE);
    }

    @Override
    public int write(ExecutionOptions options) {
      return operations.writeEmptyBatch(
          table, entities, Objects.requireNonNull(options, "options"));
    }

    @Override
    public ReturningWrite<List<E>> returning() {
      return emptyReturning();
    }

    @Override
    public <R> ReturningWrite<List<R>> returning(ProjectionSelection<R> projection) {
      MutationQuerySupport.validateReturningProjection(
          table, Objects.requireNonNull(projection, "projection"));
      return emptyReturning();
    }

    @Override
    public <R> ReturningWrite<List<R>> returning(ProjectionType<R> projection) {
      MutationQuerySupport.validateReturningProjection(
          table, Objects.requireNonNull(projection, "projection"));
      return emptyReturning();
    }

    @SuppressWarnings("Convert2Diamond")
    private <R> ReturningWrite<List<R>> emptyReturning() {
      return new Returning<>(
          options -> {
            operations.writeEmptyBatch(table, entities, options);
            return new DefaultWritten<List<R>>(0, null);
          });
    }

    @Override
    public String toString() {
      return "BatchInsertWrite[values redacted]";
    }
  }

  abstract static class Conditional<E> {
    final DefaultMutationOperations operations;
    final QueryTable<E> table;
    final List<MutationQuerySupport.Assignment> assignments;
    final @Nullable QueryCondition condition;
    final boolean allRows;
    final boolean update;
    private volatile @Nullable Prepared prepared;
    final DynamicMutationPlans.Request<CompiledMutationPlan<@Nullable Object[]>> countPlan =
        new DynamicMutationPlans.Request<>();

    Conditional(
        DefaultMutationOperations operations,
        QueryTable<E> table,
        List<MutationQuerySupport.Assignment> assignments,
        @Nullable QueryCondition condition,
        boolean allRows,
        boolean update) {
      this.operations = operations;
      this.table = Objects.requireNonNull(table, "table");
      this.assignments = assignments;
      this.condition = condition;
      this.allRows = allRows;
      this.update = update;
    }

    final Prepared prepare() {
      Prepared existing = prepared;
      if (existing != null) {
        return existing;
      }
      if (update && assignments.isEmpty()) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_NO_UPDATABLE_PROPERTIES,
            "conditional UPDATE requires at least one explicit assignment");
      }
      if (condition == null && !allRows) {
        throw UnsafeMutationException.missingWhere();
      }
      synchronized (this) {
        existing = prepared;
        if (existing == null) {
          operations.requireWritable(table);
          try {
            MutationQuerySupport.Inputs inputs =
                MutationQuerySupport.inputs(
                    operations.dynamicPlans().registry(), table, assignments, condition, update);
            StatementAst statement =
                update
                    ? new UpdateStatement(
                        MutationQuerySupport.table(table), inputs.assignments(), inputs.where())
                    : new DeleteStatement(MutationQuerySupport.table(table), inputs.where());
            existing = new Prepared(statement, inputs);
          } catch (IllegalArgumentException failure) {
            throw MutationException.of(
                SkisErrorCode.MUTATION_PARAMETER_SHAPE,
                "conditional mutation has invalid assignments or predicate",
                failure);
          }
          prepared = existing;
        }
      }
      return existing;
    }

    final QueryCondition whereCondition(QueryCondition added) {
      if (condition != null) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_PARAMETER_SHAPE,
            "WHERE is already set; use and/or to combine conditions");
      }
      return Objects.requireNonNull(added, "condition");
    }

    final QueryCondition combine(QueryCondition added, boolean conjunction) {
      QueryCondition current = condition;
      if (current == null) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_PARAMETER_SHAPE, "and/or requires an existing WHERE condition");
      }
      return conjunction ? current.and(added) : current.or(added);
    }

    public final int write() {
      return write(ExecutionOptions.NONE);
    }

    public final int write(ExecutionOptions options) {
      return operations.writeConditional(this, Objects.requireNonNull(options, "options"));
    }

    public final ReturningWrite<List<E>> returning() {
      return returning(MutationQuerySupport.result(table));
    }

    public final <R> ReturningWrite<List<R>> returning(ProjectionSelection<R> projection) {
      return returning(
          MutationQuerySupport.result(table, Objects.requireNonNull(projection, "projection")));
    }

    public final <R> ReturningWrite<List<R>> returning(ProjectionType<R> projection) {
      return returning(
          MutationQuerySupport.result(table, Objects.requireNonNull(projection, "projection")));
    }

    private <R> ReturningWrite<List<R>> returning(MutationQuerySupport.Result<R> result) {
      var request = new DynamicMutationPlans.Request<DynamicMutationPlans.ConditionalPlan<R>>();
      return new Returning<>(
          options -> operations.writeConditionalReturning(this, options, result, request));
    }

    @Override
    public final String toString() {
      return "ConditionalWrite[values redacted]";
    }
  }

  private static final class Update<E> extends Conditional<E> implements UpdateWrite<E> {
    Update(
        DefaultMutationOperations operations,
        QueryTable<E> table,
        List<MutationQuerySupport.Assignment> assignments,
        @Nullable QueryCondition condition,
        boolean allRows) {
      super(operations, table, assignments, condition, allRows, true);
    }

    @Override
    public <V> UpdateWrite<E> set(NonNullSelectable<V> column, V value) {
      return set((Selectable<V>) column, Objects.requireNonNull(value, "value"));
    }

    @Override
    public <V> UpdateWrite<E> set(Selectable<V> column, @Nullable V value) {
      return assignment(new MutationQuerySupport.Assignment(column, value, null));
    }

    @Override
    public <V> UpdateWrite<E> setExpression(
        NonNullSelectable<V> column, NonNullSelectable<V> value) {
      return setExpression((Selectable<V>) column, value);
    }

    @Override
    public <V> UpdateWrite<E> setExpression(Selectable<V> column, Selectable<V> value) {
      return assignment(
          new MutationQuerySupport.Assignment(
              column, null, Objects.requireNonNull(value, "value")));
    }

    private UpdateWrite<E> assignment(MutationQuerySupport.Assignment input) {
      operations.requireWritable(table);
      var column = MutationQuerySupport.column(table, input.column());
      var property = column.property();
      if (!property.column().updatable()
          || table.entity().primaryKey().orElseThrow().properties().contains(property)
          || table
              .entity()
              .version()
              .map(version -> version.property() == property)
              .orElse(false)) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_PARAMETER_SHAPE,
            "SET cannot write a primary key, version or non-updatable column");
      }
      List<MutationQuerySupport.Assignment> next = new ArrayList<>(assignments.size() + 1);
      for (var existing : assignments) {
        if (MutationQuerySupport.column(table, existing.column()).property() == property) {
          throw MutationException.of(
              SkisErrorCode.MUTATION_PARAMETER_SHAPE,
              "SET cannot assign a target column more than once");
        }
        next.add(existing);
      }
      next.add(input);
      return new Update<>(operations, table, List.copyOf(next), condition, allRows);
    }

    @Override
    public UpdateWrite<E> where(QueryCondition added) {
      return new Update<>(operations, table, assignments, whereCondition(added), allRows);
    }

    @Override
    public UpdateWrite<E> and(QueryCondition added) {
      return new Update<>(operations, table, assignments, combine(added, true), allRows);
    }

    @Override
    public UpdateWrite<E> or(QueryCondition added) {
      return new Update<>(operations, table, assignments, combine(added, false), allRows);
    }

    @Override
    public UpdateWrite<E> allowAllRows() {
      return new Update<>(operations, table, assignments, condition, true);
    }
  }

  private static final class Delete<E> extends Conditional<E> implements DeleteWrite<E> {
    Delete(
        DefaultMutationOperations operations,
        QueryTable<E> table,
        @Nullable QueryCondition condition,
        boolean allRows) {
      super(operations, table, List.of(), condition, allRows, false);
    }

    @Override
    public DeleteWrite<E> where(QueryCondition added) {
      return new Delete<>(operations, table, whereCondition(added), allRows);
    }

    @Override
    public DeleteWrite<E> and(QueryCondition added) {
      return new Delete<>(operations, table, combine(added, true), allRows);
    }

    @Override
    public DeleteWrite<E> or(QueryCondition added) {
      return new Delete<>(operations, table, combine(added, false), allRows);
    }

    @Override
    public DeleteWrite<E> allowAllRows() {
      return new Delete<>(operations, table, condition, true);
    }
  }

  record Prepared(StatementAst statement, MutationQuerySupport.Inputs inputs) {}
}
