package io.skis.mutation.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.MutationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.dialect.spi.ResolvedStatement;
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
import io.skis.query.DerivedRelation;
import io.skis.query.JoinOnStep;
import io.skis.query.NonNullSelectable;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;
import io.skis.query.RowColumns;
import io.skis.query.SelectSource;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnQuery;
import io.skis.query.Sql;
import io.skis.query.internal.MutationQuerySupport;
import io.skis.sql.ast.DeleteStatement;
import io.skis.sql.ast.JoinType;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.UpdateStatement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Value-bearing immutable request stages; none of these objects enter shared plan caches. */
final class MutationRequests {

  private MutationRequests() {}

  private static QueryCondition requireOn(QueryCondition on) {
    return Objects.requireNonNull(on, "condition");
  }

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
    return new Update<>(operations, table, List.of(), null, false, null);
  }

  static <E> DeleteWrite<E> delete(DefaultMutationOperations operations, QueryTable<E> table) {
    return new Delete<>(operations, table, null, false, null);
  }

  private record Returning<R extends @Nullable Object>(
      Function<ExecutionOptions, Written<R>> execute) implements ReturningWrite<R> {
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
      return BatchWrites.input(
          operations,
          table,
          List.copyOf(Objects.requireNonNull(entities, "entities")),
          BatchWrites.Kind.INSERT);
    }

    @Override
    public String toString() {
      return "InsertInto[values redacted]";
    }
  }

  private static final class Single<E> extends WriteSelections<E> implements InsertWrite<E> {
    private final E entity;

    Single(DefaultMutationOperations operations, QueryTable<E> table, E entity) {
      super(operations, table);
      this.entity = entity;
    }

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
    <R extends @Nullable Object> ReturningWrite<R> outputs(MutationQuerySupport.Result<R> result) {
      return StructuredWrites.entityReturning(operations, table, entity, result);
    }

    @Override
    public String toString() {
      return "InsertWrite[values redacted]";
    }
  }

  abstract static class WriteRequest<E> extends WriteSelections<E> {
    // Intermediate builder stages never allocate plan lookups; only executed stages do.
    private volatile DynamicMutationPlans.@Nullable Request<
            CompiledMutationPlan<@Nullable Object[]>>
        countPlan;

    WriteRequest(DefaultMutationOperations operations, QueryTable<E> table) {
      super(operations, Objects.requireNonNull(table, "table"));
    }

    final DynamicMutationPlans.Request<CompiledMutationPlan<@Nullable Object[]>> countPlan() {
      var known = countPlan;
      if (known != null) return known;
      synchronized (this) {
        known = countPlan;
        if (known == null) countPlan = known = new DynamicMutationPlans.Request<>();
      }
      return known;
    }

    private volatile @Nullable Prepared prepared;

    final Prepared prepare() {
      Prepared known = prepared;
      if (known != null) return known;
      synchronized (this) {
        known = prepared;
        if (known == null) prepared = known = compileChecked(null);
      }
      return known;
    }

    final Prepared compileChecked(MutationQuerySupport.@Nullable Result<?> returning) {
      try {
        return compile(returning);
      } catch (IllegalArgumentException failure) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_PARAMETER_SHAPE,
            "write has invalid assignments, sources, outputs or predicate",
            failure);
      }
    }

    abstract Prepared compile(MutationQuerySupport.@Nullable Result<?> returning);

    abstract ExecutionOperationKind operationKind();

    void completed(int rows) {}

    /** INSERT, MERGE, UPDATE and DELETE labels are the constant plan operation names. */
    final String operationName() {
      return operationKind().label();
    }

    public final int write() {
      return write(ExecutionOptions.NONE);
    }

    public final int write(ExecutionOptions options) {
      return operations.writeConditional(this, Objects.requireNonNull(options, "options"));
    }

    @Override
    @SuppressWarnings("Convert2Diamond")
    final <R extends @Nullable Object> ReturningWrite<R> outputs(
        MutationQuerySupport.Result<R> result) {
      var request = new DynamicMutationPlans.Request<DynamicMutationPlans.ConditionalPlan<R>>();
      ReturningInput<E, R> input = new ReturningInput<E, R>(this, result);
      return new Returning<R>(
          options -> operations.writeConditionalReturning(input, options, request));
    }
  }

  static final class ReturningInput<E, R extends @Nullable Object> {
    final WriteRequest<E> request;
    final MutationQuerySupport.Result<R> result;
    private volatile @Nullable Prepared prepared;

    ReturningInput(WriteRequest<E> request, MutationQuerySupport.Result<R> result) {
      this.request = request;
      this.result = result;
    }

    Prepared prepare() {
      Prepared known = prepared;
      if (known != null) return known;
      synchronized (this) {
        known = prepared;
        if (known == null) prepared = known = request.compileChecked(result);
      }
      return known;
    }
  }

  abstract static class Conditional<E> extends WriteRequest<E> {
    final List<MutationQuerySupport.Assignment> assignments;
    final @Nullable QueryCondition condition;
    final boolean allRows;
    final boolean update;
    final MutationQuerySupport.@Nullable Sources sources;

    Conditional(
        DefaultMutationOperations operations,
        QueryTable<E> table,
        List<MutationQuerySupport.Assignment> assignments,
        @Nullable QueryCondition condition,
        boolean allRows,
        boolean update,
        MutationQuerySupport.@Nullable Sources sources) {
      super(operations, table);
      this.assignments = assignments;
      this.condition = condition;
      this.allRows = allRows;
      this.update = update;
      this.sources = sources;
    }

    @Override
    final Prepared compile(MutationQuerySupport.@Nullable Result<?> returning) {
      if (update && assignments.isEmpty())
        throw MutationException.of(
            SkisErrorCode.MUTATION_NO_UPDATABLE_PROPERTIES,
            "conditional UPDATE requires at least one explicit assignment");
      if (condition == null && !allRows) throw UnsafeMutationException.missingWhere();
      operations.requireWritable(table);
      try {
        MutationQuerySupport.Inputs inputs =
            MutationQuerySupport.inputs(
                operations.dynamicPlans().registry(),
                table,
                assignments,
                condition,
                sources,
                returning);
        if (update)
          WriteCodecs.requireRowAssignments(
              operations.dynamicPlans().registry(),
              inputs.assignments(),
              inputs.sources(),
              inputs.codecs());
        StatementAst statement =
            update
                ? new UpdateStatement(
                    MutationQuerySupport.table(table),
                    inputs.assignments(),
                    inputs.where(),
                    inputs.sources())
                : new DeleteStatement(
                    MutationQuerySupport.table(table), inputs.where(), inputs.sources());
        return new Prepared(statement, inputs);
      } catch (IllegalArgumentException failure) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_PARAMETER_SHAPE,
            "conditional mutation has invalid assignments, outputs or predicate",
            failure);
      }
    }

    @Override
    final ExecutionOperationKind operationKind() {
      return update ? ExecutionOperationKind.UPDATE : ExecutionOperationKind.DELETE;
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
        boolean allRows,
        MutationQuerySupport.@Nullable Sources sources) {
      super(operations, table, assignments, condition, allRows, true, sources);
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
    public <V> UpdateWrite<E> set(NonNullSelectable<V> column, NonNullSelectable<V> value) {
      return set((Selectable<V>) column, value);
    }

    @Override
    public <V> UpdateWrite<E> set(Selectable<V> column, Selectable<V> value) {
      return assignment(
          new MutationQuerySupport.Assignment(
              column, null, Objects.requireNonNull(value, "value")));
    }

    @Override
    public UpdateWrite<E> from(QueryTable<?> source) {
      if (sources != null) throw StructuredWrites.invalid("FROM is already declared");
      return new Update<>(
          operations, table, assignments, condition, allRows, MutationQuerySupport.source(source));
    }

    @Override
    public JoinOnStep<UpdateWrite<E>> join(QueryTable<?> source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Update<>(
              operations,
              table,
              assignments,
              condition,
              allRows,
              current.join(source, JoinType.INNER, requireOn(on)));
    }

    @Override
    public JoinOnStep<UpdateWrite<E>> innerJoin(QueryTable<?> source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Update<>(
              operations,
              table,
              assignments,
              condition,
              allRows,
              current.join(source, JoinType.INNER, requireOn(on)));
    }

    @Override
    public JoinOnStep<UpdateWrite<E>> leftJoin(QueryTable<?> source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Update<>(
              operations,
              table,
              assignments,
              condition,
              allRows,
              current.join(source, JoinType.LEFT, requireOn(on)));
    }

    @Override
    public JoinOnStep<UpdateWrite<E>> rightJoin(QueryTable<?> source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Update<>(
              operations,
              table,
              assignments,
              condition,
              allRows,
              current.join(source, JoinType.RIGHT, requireOn(on)));
    }

    @Override
    public JoinOnStep<UpdateWrite<E>> fullJoin(QueryTable<?> source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Update<>(
              operations,
              table,
              assignments,
              condition,
              allRows,
              current.join(source, JoinType.FULL, requireOn(on)));
    }

    @Override
    public UpdateWrite<E> crossJoin(QueryTable<?> source) {
      return new Update<>(
          operations,
          table,
          assignments,
          condition,
          allRows,
          requireSources().join(source, JoinType.CROSS, null));
    }

    @Override
    public UpdateWrite<E> from(DerivedRelation source) {
      if (sources != null) throw StructuredWrites.invalid("FROM is already declared");
      return new Update<>(
          operations, table, assignments, condition, allRows, MutationQuerySupport.source(source));
    }

    @Override
    public JoinOnStep<UpdateWrite<E>> join(DerivedRelation source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Update<>(
              operations,
              table,
              assignments,
              condition,
              allRows,
              current.join(source, JoinType.INNER, requireOn(on)));
    }

    @Override
    public JoinOnStep<UpdateWrite<E>> innerJoin(DerivedRelation source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Update<>(
              operations,
              table,
              assignments,
              condition,
              allRows,
              current.join(source, JoinType.INNER, requireOn(on)));
    }

    @Override
    public JoinOnStep<UpdateWrite<E>> leftJoin(DerivedRelation source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Update<>(
              operations,
              table,
              assignments,
              condition,
              allRows,
              current.join(source, JoinType.LEFT, requireOn(on)));
    }

    @Override
    public JoinOnStep<UpdateWrite<E>> rightJoin(DerivedRelation source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Update<>(
              operations,
              table,
              assignments,
              condition,
              allRows,
              current.join(source, JoinType.RIGHT, requireOn(on)));
    }

    @Override
    public JoinOnStep<UpdateWrite<E>> fullJoin(DerivedRelation source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Update<>(
              operations,
              table,
              assignments,
              condition,
              allRows,
              current.join(source, JoinType.FULL, requireOn(on)));
    }

    @Override
    public UpdateWrite<E> crossJoin(DerivedRelation source) {
      return new Update<>(
          operations,
          table,
          assignments,
          condition,
          allRows,
          requireSources().join(source, JoinType.CROSS, null));
    }

    private MutationQuerySupport.Sources requireSources() {
      if (sources == null) throw StructuredWrites.invalid("a source JOIN requires FROM");
      return sources;
    }

    @Override
    public <V> UpdateWrite<E> set(
        Selectable<V> column, SingleColumnQuery<? extends @Nullable V> query) {
      return set(column, Sql.scalar(query));
    }

    @Override
    public <V> UpdateWrite<E> setNull(Selectable<V> column) {
      return assignment(MutationQuerySupport.setNull(column));
    }

    @Override
    public <V> UpdateWrite<E> setDefault(Selectable<V> column) {
      return assignment(MutationQuerySupport.setDefault(column));
    }

    @Override
    public UpdateWrite<E> set(RowColumns columns, SelectSource<?> query) {
      return assignment(MutationQuerySupport.rowAssignment(columns, query));
    }

    private UpdateWrite<E> assignment(MutationQuerySupport.Assignment input) {
      operations.requireWritable(table);
      BitSet assigned = new BitSet(table.entity().properties().size());
      for (var existing : assignments)
        for (var selected : existing.columns())
          assigned.set(MutationQuerySupport.column(table, selected).property().ordinal());
      var primaryKey = table.entity().primaryKey().orElseThrow().properties();
      for (var selected : input.columns()) {
        var property = MutationQuerySupport.column(table, selected).property();
        if (!property.column().updatable()
            || primaryKey.contains(property)
            || assigned.get(property.ordinal()))
          throw StructuredWrites.invalid(
              "SET contains a duplicate, primary-key or non-updatable target");
        assigned.set(property.ordinal());
      }
      List<MutationQuerySupport.Assignment> next = new ArrayList<>(assignments.size() + 1);
      next.addAll(assignments);
      next.add(input);
      return new Update<>(operations, table, List.copyOf(next), condition, allRows, sources);
    }

    @Override
    public UpdateWrite<E> where(QueryCondition added) {
      return new Update<>(operations, table, assignments, whereCondition(added), allRows, sources);
    }

    @Override
    public UpdateWrite<E> and(QueryCondition added) {
      return new Update<>(operations, table, assignments, combine(added, true), allRows, sources);
    }

    @Override
    public UpdateWrite<E> or(QueryCondition added) {
      return new Update<>(operations, table, assignments, combine(added, false), allRows, sources);
    }

    @Override
    public UpdateWrite<E> allowAllRows() {
      return new Update<>(operations, table, assignments, condition, true, sources);
    }
  }

  private static final class Delete<E> extends Conditional<E> implements DeleteWrite<E> {
    Delete(
        DefaultMutationOperations operations,
        QueryTable<E> table,
        @Nullable QueryCondition condition,
        boolean allRows,
        MutationQuerySupport.@Nullable Sources sources) {
      super(operations, table, List.of(), condition, allRows, false, sources);
    }

    @Override
    public DeleteWrite<E> using(QueryTable<?> source) {
      if (sources != null) throw StructuredWrites.invalid("USING is already declared");
      return new Delete<>(
          operations, table, condition, allRows, MutationQuerySupport.source(source));
    }

    @Override
    public JoinOnStep<DeleteWrite<E>> join(QueryTable<?> source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Delete<>(
              operations,
              table,
              condition,
              allRows,
              current.join(source, JoinType.INNER, requireOn(on)));
    }

    @Override
    public JoinOnStep<DeleteWrite<E>> innerJoin(QueryTable<?> source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Delete<>(
              operations,
              table,
              condition,
              allRows,
              current.join(source, JoinType.INNER, requireOn(on)));
    }

    @Override
    public JoinOnStep<DeleteWrite<E>> leftJoin(QueryTable<?> source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Delete<>(
              operations,
              table,
              condition,
              allRows,
              current.join(source, JoinType.LEFT, requireOn(on)));
    }

    @Override
    public JoinOnStep<DeleteWrite<E>> rightJoin(QueryTable<?> source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Delete<>(
              operations,
              table,
              condition,
              allRows,
              current.join(source, JoinType.RIGHT, requireOn(on)));
    }

    @Override
    public JoinOnStep<DeleteWrite<E>> fullJoin(QueryTable<?> source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Delete<>(
              operations,
              table,
              condition,
              allRows,
              current.join(source, JoinType.FULL, requireOn(on)));
    }

    @Override
    public DeleteWrite<E> crossJoin(QueryTable<?> source) {
      return new Delete<>(
          operations,
          table,
          condition,
          allRows,
          requireSources().join(source, JoinType.CROSS, null));
    }

    @Override
    public DeleteWrite<E> using(DerivedRelation source) {
      if (sources != null) throw StructuredWrites.invalid("USING is already declared");
      return new Delete<>(
          operations, table, condition, allRows, MutationQuerySupport.source(source));
    }

    @Override
    public JoinOnStep<DeleteWrite<E>> join(DerivedRelation source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Delete<>(
              operations,
              table,
              condition,
              allRows,
              current.join(source, JoinType.INNER, requireOn(on)));
    }

    @Override
    public JoinOnStep<DeleteWrite<E>> innerJoin(DerivedRelation source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Delete<>(
              operations,
              table,
              condition,
              allRows,
              current.join(source, JoinType.INNER, requireOn(on)));
    }

    @Override
    public JoinOnStep<DeleteWrite<E>> leftJoin(DerivedRelation source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Delete<>(
              operations,
              table,
              condition,
              allRows,
              current.join(source, JoinType.LEFT, requireOn(on)));
    }

    @Override
    public JoinOnStep<DeleteWrite<E>> rightJoin(DerivedRelation source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Delete<>(
              operations,
              table,
              condition,
              allRows,
              current.join(source, JoinType.RIGHT, requireOn(on)));
    }

    @Override
    public JoinOnStep<DeleteWrite<E>> fullJoin(DerivedRelation source) {
      MutationQuerySupport.Sources current = requireSources();
      return on ->
          new Delete<>(
              operations,
              table,
              condition,
              allRows,
              current.join(source, JoinType.FULL, requireOn(on)));
    }

    @Override
    public DeleteWrite<E> crossJoin(DerivedRelation source) {
      return new Delete<>(
          operations,
          table,
          condition,
          allRows,
          requireSources().join(source, JoinType.CROSS, null));
    }

    private MutationQuerySupport.Sources requireSources() {
      if (sources == null) throw StructuredWrites.invalid("a source JOIN requires USING");
      return sources;
    }

    @Override
    public DeleteWrite<E> where(QueryCondition added) {
      return new Delete<>(operations, table, whereCondition(added), allRows, sources);
    }

    @Override
    public DeleteWrite<E> and(QueryCondition added) {
      return new Delete<>(operations, table, combine(added, true), allRows, sources);
    }

    @Override
    public DeleteWrite<E> or(QueryCondition added) {
      return new Delete<>(operations, table, combine(added, false), allRows, sources);
    }

    @Override
    public DeleteWrite<E> allowAllRows() {
      return new Delete<>(operations, table, condition, true, sources);
    }
  }

  /** Per-request occurrence analysis; structure equality alone cannot prove scope validity. */
  record Prepared(MutationQuerySupport.Inputs inputs, ResolvedStatement resolved) {
    Prepared(StatementAst statement, MutationQuerySupport.Inputs inputs) {
      this(inputs, ResolvedStatement.analyze(statement));
    }
  }
}
