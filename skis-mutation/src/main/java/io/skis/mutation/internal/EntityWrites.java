package io.skis.mutation.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.MutationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.spi.ExecutionContext;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.core.spi.StatementPurpose;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.metadata.PropertyMeta;
import io.skis.mutation.BatchWrite;
import io.skis.mutation.EntityById;
import io.skis.mutation.OptimisticLockException;
import io.skis.mutation.ReturningWrite;
import io.skis.mutation.SingleWrite;
import io.skis.mutation.Written;
import io.skis.query.QueryTable;
import io.skis.query.internal.MutationQuerySupport;
import io.skis.query.internal.WriteInputCompiler;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.IncrementExpression;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.UpdateAssignment;
import io.skis.sql.ast.UpdateStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Entity by-ID stages retain generated binders and metadata version semantics. */
final class EntityWrites {
  private EntityWrites() {}

  static <E> EntityById<E> start(
      DefaultMutationOperations operations, QueryTable<E> table, boolean delete) {
    Objects.requireNonNull(table, "table");
    return new EntityById<>() {
      @Override
      public SingleWrite<E> value(E entity) {
        return new Single<>(operations, table, Objects.requireNonNull(entity), delete);
      }

      @Override
      public BatchWrite<E> values(Collection<? extends E> entities) {
        return BatchWrites.input(
            operations,
            table,
            List.copyOf(entities),
            delete ? BatchWrites.Kind.DELETE : BatchWrites.Kind.UPDATE);
      }
    };
  }

  private static final class Single<E> extends WriteSelections<E> implements SingleWrite<E> {
    final E entity;
    final boolean delete;

    Single(DefaultMutationOperations operations, QueryTable<E> table, E entity, boolean delete) {
      super(operations, table);
      this.entity = entity;
      this.delete = delete;
    }

    @Override
    public int write() {
      return write(ExecutionOptions.NONE);
    }

    @Override
    public int write(ExecutionOptions options) {
      operations.beforeWrite();
      var observation =
          operations.startObservation(
              delete ? ExecutionOperationKind.DELETE_BY_ID : ExecutionOperationKind.UPDATE_BY_ID);
      try {
        var plans = operations.plans(table);
        validateEntity(plans, entity);
        boolean checked = plans.readExpectedVersion(entity) != null;
        int count =
            operations.jdbcExecutor.executeUpdate(
                plans.entityPlan(delete, checked),
                entity,
                ExecutionContext.of(options),
                observation,
                StatementPurpose.PRIMARY);
        validateCount(count, checked);
        DefaultMutationOperations.completeSucceeded(observation);
        return count;
      } catch (RuntimeException | Error failure) {
        DefaultMutationOperations.completeFailed(observation, failure);
        throw failure;
      }
    }

    @Override
    <R extends @Nullable Object> ReturningWrite<R> outputs(MutationQuerySupport.Result<R> result) {
      return new Returning<>(this, result);
    }

    @Override
    public String toString() {
      return "EntityByIdWrite[values redacted]";
    }
  }

  static <E> void validateEntity(EntityMutationPlanSet<E> plans, E entity) {
    DefaultMutationOperations.requireEntityValue(plans.entity(), entity);
    Object id = DefaultMutationOperations.readId(plans, entity);
    if (!plans.idProperty().javaType().isInstance(id))
      throw MutationException.of(
          SkisErrorCode.MUTATION_ID_VALUE_INVALID,
          "entity byId requires a non-null primary key of its declared type");
  }

  private static void validateCount(int count, boolean checked) {
    if (checked && count == 0) throw OptimisticLockException.of("optimistic entity byId conflict");
    if (count < 0 || count > 1)
      throw MutationException.of(
          SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
          "entity byId affected more than one row or returned an inexact count");
  }

  private record Entry<R extends @Nullable Object>(
      StructuredWrites.Prepared prepared,
      DynamicMutationPlans.Request<DynamicMutationPlans.StructuredPlan<R>> plan) {}

  private static final class Returning<E, R extends @Nullable Object> implements ReturningWrite<R> {
    final Single<E> input;
    final MutationQuerySupport.Result<R> result;
    private volatile @Nullable Entry<R> checked, unchecked;

    Returning(Single<E> input, MutationQuerySupport.Result<R> result) {
      this.input = input;
      this.result = result;
    }

    private Entry<R> entry(EntityMutationPlanSet<E> plans, boolean checking) {
      Entry<R> entry = checking ? checked : unchecked;
      if (entry != null) return entry;
      synchronized (this) {
        entry = checking ? checked : unchecked;
        if (entry == null) {
          entry = compile(plans, checking);
          if (checking) checked = entry;
          else unchecked = entry;
        }
      }
      return entry;
    }

    private Entry<R> compile(EntityMutationPlanSet<E> plans, boolean checking) {
      try {
        var prefix = plans.entityPlan(input.delete, checking);
        WriteInputCompiler compiler =
            new WriteInputCompiler(
                input.operations.dynamicPlans().registry(), input.table, prefix.parameterCount());
        StatementAst statement = statement(input.table, input.delete, checking);
        compiler.returning(result);
        var values = compiler.finish();
        var prepared =
            new StructuredWrites.Prepared(
                ResolvedStatement.analyze(statement),
                values,
                prefix.parameterBinder(),
                prefix.parameterCount(),
                plans.structuredIdentity(),
                plans,
                compiler.column(plans.idProperty()),
                checking,
                -1,
                new StructuredWrites.Arguments(input.entity, values.values()),
                input.delete ? "deleteById" : "updateById",
                0);
        return new Entry<>(prepared, new DynamicMutationPlans.Request<>());
      } catch (IllegalArgumentException failure) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_PARAMETER_SHAPE,
            "entity byId RETURNING has invalid outputs",
            failure);
      }
    }

    @Override
    public Written<R> write() {
      return write(ExecutionOptions.NONE);
    }

    @Override
    public Written<R> write(ExecutionOptions options) {
      var operations = input.operations;
      operations.beforeWrite();
      var observation =
          operations.startObservation(
              input.delete
                  ? ExecutionOperationKind.DELETE_BY_ID
                  : ExecutionOperationKind.UPDATE_BY_ID);
      try {
        var plans = operations.plans(input.table);
        validateEntity(plans, input.entity);
        boolean checking = plans.readExpectedVersion(input.entity) != null;
        var entry = entry(plans, checking);
        var plan =
            operations
                .dynamicPlans()
                .structured(entry.plan(), entry.prepared(), result, false, observation);
        var row =
            operations.jdbcExecutor.executeReturningOptional(
                plan.statement(),
                Objects.requireNonNull(plan.decoder()),
                plan.columnCount(),
                entry.prepared().fixedArguments(),
                ExecutionContext.of(options),
                observation,
                StatementPurpose.PRIMARY);
        int count = row == null ? 0 : 1;
        validateCount(count, checking);
        Written<R> written = new DefaultWritten<>(count, row == null ? null : row.value());
        DefaultMutationOperations.completeSucceeded(observation);
        return written;
      } catch (RuntimeException | Error failure) {
        DefaultMutationOperations.completeFailed(observation, failure);
        throw failure;
      }
    }

    @Override
    public String toString() {
      return "EntityByIdReturning[values redacted]";
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  static StatementAst statement(QueryTable<?> table, boolean delete, boolean checked) {
    var target = MutationQuerySupport.table(table);
    var id = table.entity().primaryKey().orElseThrow().properties().getFirst();
    List<UpdateAssignment<?>> assignments = new ArrayList<>();
    int ordinal = 0;
    if (!delete)
      for (var property : table.entity().properties()) {
        if (!property.column().updatable()
            || property == id
            || table
                .entity()
                .version()
                .map(version -> version.property() == property)
                .orElse(false)) continue;
        ColumnExpression column = column(table, property);
        assignments.add(
            new UpdateAssignment(
                column,
                new ParameterSlot(ordinal++, property.javaType(), property.column().nullable())));
      }
    if (!delete && table.entity().version().isPresent()) {
      ColumnExpression version = column(table, table.entity().version().orElseThrow().property());
      assignments.add(new UpdateAssignment(version, new IncrementExpression(version)));
    }
    List<SqlPredicate> predicates = new ArrayList<>(2);
    predicates.add(
        new ComparisonPredicate(
            column(table, id),
            ComparisonOperator.EQUAL,
            new ParameterSlot(ordinal++, id.javaType(), false)));
    if (checked) {
      var property = table.entity().version().orElseThrow().property();
      predicates.add(
          new ComparisonPredicate(
              column(table, property),
              ComparisonOperator.EQUAL,
              new ParameterSlot(ordinal, property.javaType(), false)));
    }
    SqlPredicate where =
        predicates.size() == 1 ? predicates.getFirst() : LogicalPredicate.and(predicates);
    return delete
        ? new DeleteStatement(target, where)
        : new UpdateStatement(target, assignments, where);
  }

  private static ColumnExpression<?, ?> column(QueryTable<?> table, PropertyMeta<?, ?> property) {
    return MutationQuerySupport.column(table, table.columns().get(property.ordinal()));
  }
}
