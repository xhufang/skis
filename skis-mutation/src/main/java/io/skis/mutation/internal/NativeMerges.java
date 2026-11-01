package io.skis.mutation.internal;

import io.skis.core.MutationException;
import io.skis.core.SkisErrorCode;
import io.skis.core.spi.ExecutionOperationKind;
import io.skis.metadata.IdGeneration;
import io.skis.mutation.ExpressionRow;
import io.skis.mutation.MergeActions;
import io.skis.mutation.MergeMatched;
import io.skis.mutation.MergeNotMatched;
import io.skis.mutation.MergeOn;
import io.skis.mutation.MergeUpdate;
import io.skis.mutation.MergeUpdateSet;
import io.skis.mutation.MergeWrite;
import io.skis.mutation.OptimisticLockException;
import io.skis.query.DerivedRelation;
import io.skis.query.NonNullSelectable;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;
import io.skis.query.RowColumns;
import io.skis.query.SelectSource;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnQuery;
import io.skis.query.Sql;
import io.skis.query.ValuesSource;
import io.skis.query.internal.MutationQuerySupport;
import io.skis.query.internal.MutationQuerySupport.Assignment;
import io.skis.query.internal.WriteInputCompiler;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.LogicalOperator;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.MergeAction;
import io.skis.sql.ast.MergeStatement;
import io.skis.sql.ast.ParameterRelationSource;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.RelationSource;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** One immutable source/action model and one parameter namespace for native MERGE. */
final class NativeMerges {

  private NativeMerges() {}

  static <E> MergeOn<E> using(
      DefaultMutationOperations operations, QueryTable<E> target, QueryTable<?> source) {
    return new Request<>(
        operations, target, Draft.start(new TableSource(Objects.requireNonNull(source))));
  }

  static <E> MergeOn<E> using(
      DefaultMutationOperations operations, QueryTable<E> target, DerivedRelation source) {
    return new Request<>(
        operations, target, Draft.start(new DerivedSource(Objects.requireNonNull(source))));
  }

  static <E> MergeOn<E> using(
      DefaultMutationOperations operations, QueryTable<E> target, ValuesSource source) {
    return new Request<>(
        operations, target, Draft.start(new ValuesInput(Objects.requireNonNull(source))));
  }

  static <E> MergeWrite<E> insert(MergeNotMatched<E> stage, Consumer<ExpressionRow> collect) {
    if (!(stage instanceof Request<E> request))
      throw StructuredWrites.invalid("MERGE stage was not created by SKIS");
    return request.insert(collect);
  }

  private sealed interface Source permits TableSource, DerivedSource, ValuesInput {

    RelationSource compile(WriteInputCompiler compiler);

    default boolean metadataVersion() {
      return false;
    }
  }

  private record TableSource(QueryTable<?> table) implements Source {

    @Override
    public RelationSource compile(WriteInputCompiler compiler) {
      return compiler.relation(table);
    }
  }

  private record DerivedSource(DerivedRelation relation) implements Source {

    @Override
    public RelationSource compile(WriteInputCompiler compiler) {
      return compiler.relation(relation);
    }
  }

  private record ValuesInput(ValuesSource values) implements Source {

    @Override
    public RelationSource compile(WriteInputCompiler compiler) {
      return compiler.source(values);
    }

    @Override
    public boolean metadataVersion() {
      return true;
    }
  }

  private enum Pending {
    NONE,
    MATCHED,
    UPDATE,
    INSERT
  }

  private record Action(
      MergeAction.Kind kind,
      @Nullable QueryCondition condition,
      List<Assignment> assignments,
      List<Selectable<?>> columns,
      List<Selectable<?>> values) {}

  private record Draft(
      Source source,
      @Nullable QueryCondition on,
      List<Action> actions,
      Pending pending,
      @Nullable QueryCondition condition,
      @Nullable Number expected,
      @Nullable NonNullSelectable<? extends Number> expectedExpression) {
    static Draft start(Source source) {
      return new Draft(source, null, List.of(), Pending.NONE, null, null, null);
    }

    Draft pending(Pending pending, @Nullable QueryCondition condition) {
      return new Draft(source, on, actions, pending, condition, expected, expectedExpression);
    }

    Draft actions(List<Action> actions) {
      return new Draft(
          source, on, List.copyOf(actions), Pending.NONE, null, expected, expectedExpression);
    }
  }

  private static final class Request<E> extends MutationRequests.WriteRequest<E>
      implements MergeOn<E>, MergeMatched<E>, MergeUpdate<E>, MergeNotMatched<E> {
    private final Draft draft;

    Request(DefaultMutationOperations operations, QueryTable<E> table, Draft draft) {
      super(operations, table);
      this.draft = draft;
    }

    private Request<E> next(Draft draft) {
      return new Request<>(operations, table, draft);
    }

    @Override
    ExecutionOperationKind operationKind() {
      return ExecutionOperationKind.MERGE;
    }

    @Override
    public MergeActions<E> on(QueryCondition condition) {
      if (draft.on() != null) throw StructuredWrites.invalid("MERGE ON is already declared");
      return next(
          new Draft(
              draft.source(),
              Objects.requireNonNull(condition),
              draft.actions(),
              Pending.NONE,
              null,
              null,
              null));
    }

    private void requireCompleteBranch() {
      if (draft.on() == null || draft.pending() != Pending.NONE)
        throw StructuredWrites.invalid("MERGE requires ON and completion of the preceding action");
    }

    @Override
    public MergeMatched<E> whenMatched() {
      requireCompleteBranch();
      return next(draft.pending(Pending.MATCHED, null));
    }

    @Override
    public MergeNotMatched<E> whenNotMatched() {
      requireCompleteBranch();
      return next(draft.pending(Pending.INSERT, null));
    }

    @Override
    public Request<E> and(QueryCondition condition) {
      if (draft.pending() != Pending.MATCHED && draft.pending() != Pending.INSERT)
        throw StructuredWrites.invalid(
            "MERGE branch condition requires a pending MATCHED or NOT MATCHED branch");
      QueryCondition combined =
          draft.condition() == null
              ? Objects.requireNonNull(condition)
              : draft.condition().and(condition);
      return next(draft.pending(draft.pending(), combined));
    }

    @Override
    public MergeUpdateSet<E> thenUpdate() {
      if (draft.pending() != Pending.MATCHED)
        throw StructuredWrites.invalid("UPDATE requires a MATCHED branch");
      return next(draft.pending(Pending.UPDATE, draft.condition()));
    }

    @Override
    public MergeWrite<E> thenDelete() {
      if (draft.pending() != Pending.MATCHED)
        throw StructuredWrites.invalid("DELETE requires a MATCHED branch");
      return append(
          new Action(MergeAction.Kind.DELETE, draft.condition(), List.of(), List.of(), List.of()));
    }

    private Request<E> append(Action action) {
      List<Action> actions = new ArrayList<>(draft.actions().size() + 1);
      actions.addAll(draft.actions());
      actions.add(action);
      return next(draft.actions(actions));
    }

    @Override
    public <V> MergeUpdate<E> set(NonNullSelectable<V> column, V value) {
      return set((Selectable<V>) column, Objects.requireNonNull(value));
    }

    @Override
    public <V> MergeUpdate<E> set(Selectable<V> column, @Nullable V value) {
      return assignment(new MutationQuerySupport.Assignment(column, value, null));
    }

    @Override
    public <V> MergeUpdate<E> set(NonNullSelectable<V> column, NonNullSelectable<V> value) {
      return set((Selectable<V>) column, value);
    }

    @Override
    public <V> MergeUpdate<E> set(Selectable<V> column, Selectable<V> value) {
      return assignment(
          new MutationQuerySupport.Assignment(column, null, Objects.requireNonNull(value)));
    }

    @Override
    public <V> MergeUpdate<E> set(
        Selectable<V> column, SingleColumnQuery<? extends @Nullable V> query) {
      return set(column, Sql.scalar(query));
    }

    @Override
    public <V> MergeUpdate<E> setNull(Selectable<V> column) {
      return assignment(MutationQuerySupport.setNull(column));
    }

    @Override
    public <V> MergeUpdate<E> setDefault(Selectable<V> column) {
      return assignment(MutationQuerySupport.setDefault(column));
    }

    @Override
    public MergeUpdate<E> set(RowColumns columns, SelectSource<?> query) {
      return assignment(MutationQuerySupport.rowAssignment(columns, query));
    }

    private MergeUpdate<E> assignment(MutationQuerySupport.Assignment input) {
      Action current;
      boolean newAction = draft.pending() == Pending.UPDATE;
      if (newAction)
        current =
            new Action(MergeAction.Kind.UPDATE, draft.condition(), List.of(), List.of(), List.of());
      else {
        requireCompleteBranch();
        if (draft.actions().isEmpty()
            || draft.actions().getLast().kind() != MergeAction.Kind.UPDATE)
          throw StructuredWrites.invalid("SET requires a MERGE UPDATE action");
        current = draft.actions().getLast();
      }
      BitSet assigned = new BitSet();
      for (var existing : current.assignments())
        for (var column : existing.columns())
          assigned.set(MutationQuerySupport.column(table, column).property().ordinal());
      for (var column : input.columns()) {
        var property = MutationQuerySupport.column(table, column).property();
        if (!property.column().updatable()
            || table.entity().primaryKey().orElseThrow().properties().contains(property)
            || assigned.get(property.ordinal()))
          throw StructuredWrites.invalid(
              "MERGE SET contains a duplicate, primary-key or non-updatable target");
        assigned.set(property.ordinal());
      }
      List<MutationQuerySupport.Assignment> assignments = new ArrayList<>(current.assignments());
      assignments.add(input);
      Action action =
          new Action(
              MergeAction.Kind.UPDATE,
              current.condition(),
              List.copyOf(assignments),
              List.of(),
              List.of());
      if (newAction) return append(action);
      List<Action> actions = new ArrayList<>(draft.actions());
      actions.set(actions.size() - 1, action);
      return next(draft.actions(actions));
    }

    @Override
    public MergeUpdate<E> expectedVersion(Number value) {
      return expected(Objects.requireNonNull(value), null);
    }

    @Override
    public MergeUpdate<E> expectedVersionExpression(
        NonNullSelectable<? extends Number> expression) {
      return expected(null, Objects.requireNonNull(expression));
    }

    private MergeUpdate<E> expected(
        @Nullable Number value, @Nullable NonNullSelectable<? extends Number> expression) {
      requireCompleteBranch();
      if (!draft.source().metadataVersion()
          || draft.expected() != null
          || draft.expectedExpression() != null)
        throw StructuredWrites.invalid(
            "expectedVersion requires the single VALUES source and may be declared once");
      var property =
          table
              .entity()
              .version()
              .orElseThrow(() -> StructuredWrites.invalid("target has no version metadata"))
              .property();
      if (value != null
          ? !property.javaType().isInstance(value)
          : !property.javaType().equals(Objects.requireNonNull(expression).javaType())
              || expression.nullability().isNullable())
        throw StructuredWrites.invalid(
            "expected version type or nullability differs from metadata");
      return next(
          new Draft(
              draft.source(), draft.on(), draft.actions(), Pending.NONE, null, value, expression));
    }

    private MergeWrite<E> insert(Consumer<ExpressionRow> collect) {
      if (draft.pending() != Pending.INSERT)
        throw StructuredWrites.invalid("INSERT requires a NOT MATCHED branch");
      Collector collector = new Collector(table);
      try {
        Objects.requireNonNull(collect).accept(collector);
      } finally {
        collector.open = false;
      }
      if (collector.columns.isEmpty())
        throw StructuredWrites.invalid("MERGE INSERT requires explicit columns");
      return append(
          new Action(
              MergeAction.Kind.INSERT,
              draft.condition(),
              List.of(),
              List.copyOf(collector.columns),
              List.copyOf(collector.values)));
    }

    @Override
    MutationRequests.Prepared compile(MutationQuerySupport.@Nullable Result<?> returning) {
      requireCompleteBranch();
      if (draft.actions().isEmpty())
        throw StructuredWrites.invalid("MERGE requires at least one complete action");
      operations.requireWritable(table);
      if (returning != null
          && draft.actions().stream().anyMatch(action -> action.kind() == MergeAction.Kind.DELETE)
          && draft.actions().stream().anyMatch(action -> action.kind() != MergeAction.Kind.DELETE))
        throw StructuredWrites.invalid(
            "MERGE with DELETE and other actions does not support RETURNING");
      if (checking()
          && (draft.actions().stream()
                      .filter(action -> action.kind() == MergeAction.Kind.UPDATE)
                      .count()
                  != 1
              || draft.actions().stream()
                  .anyMatch(action -> action.kind() == MergeAction.Kind.DELETE)
              || draft.actions().stream()
                      .filter(action -> action.kind() == MergeAction.Kind.INSERT)
                      .count()
                  > 1))
        throw StructuredWrites.invalid(
            "expectedVersion requires one attributable UPDATE action and at most one INSERT action");
      if (checking() && draft.actions().stream().anyMatch(action -> action.condition() != null))
        throw StructuredWrites.invalid(
            "strict expectedVersion cannot combine with business branch filters");
      try {
        WriteInputCompiler compiler =
            new WriteInputCompiler(operations.dynamicPlans().registry(), table, 0);
        RelationSource source = draft.source().compile(compiler);
        SqlPredicate on = compiler.condition(Objects.requireNonNull(draft.on()));
        List<MergeAction> actions = new ArrayList<>(draft.actions().size());
        for (Action action : draft.actions()) {
          SqlPredicate condition = compiler.condition(action.condition());
          if (action.kind() == MergeAction.Kind.UPDATE) {
            var version = table.entity().version().orElse(null);
            boolean explicitVersion =
                version != null
                    && action.assignments().stream()
                        .flatMap(assignment -> assignment.columns().stream())
                        .anyMatch(
                            column ->
                                MutationQuerySupport.column(table, column).property()
                                    == version.property());
            if (checking() && explicitVersion)
              throw StructuredWrites.invalid(
                  "expectedVersion cannot combine with an explicit version assignment");
            if (checking())
              condition =
                  new LogicalPredicate(
                      LogicalOperator.AND,
                      List.of(
                          condition,
                          compiler.expectedVersion(draft.expected(), draft.expectedExpression())));
            actions.add(
                MergeAction.update(
                    condition,
                    compiler.setAssignments(
                        action.assignments(),
                        draft.source().metadataVersion() && !explicitVersion)));
          } else if (action.kind() == MergeAction.Kind.DELETE)
            actions.add(MergeAction.delete(condition));
          else {
            List<ColumnExpression<?, ?>> columns =
                new ArrayList<>(compiler.columns(action.columns()));
            List<SqlExpression<?>> values = new ArrayList<>(action.values().size() + 1);
            for (Selectable<?> value : action.values()) values.add(compiler.expression(value));
            validateInsertKey(columns);
            if (draft.source().metadataVersion()) compiler.initializeVersion(columns, values);
            actions.add(MergeAction.insert(condition, columns, values));
          }
        }
        if (checking()) validateExpectedSource(source, on, actions);
        var statement = new MergeStatement(MutationQuerySupport.table(table), source, on, actions);
        compiler.returning(returning);
        var inputs = compiler.finish();
        for (MergeAction action : actions)
          if (action.kind() == MergeAction.Kind.UPDATE)
            WriteCodecs.requireRowAssignments(
                operations.dynamicPlans().registry(),
                action.assignments(),
                statement.scope(),
                inputs.codecs());
        if (source instanceof ParameterRelationSource values) {
          var plans = operations.plans(table);
          for (MergeAction action : actions) {
            if (action.kind() == MergeAction.Kind.INSERT)
              StructuredWrites.validateInsertId(
                  plans, action.columns(), action.values(), values, inputs);
          }
        }
        return new MutationRequests.Prepared(statement, inputs);
      } catch (IllegalArgumentException failure) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_PARAMETER_SHAPE,
            "MERGE has invalid source, actions, outputs or parameters",
            failure);
      }
    }

    private void validateExpectedSource(
        RelationSource source, SqlPredicate on, List<MergeAction> actions) {
      if (!(source
              instanceof
              ParameterRelationSource(
                  List<ParameterSlot<?>> parameters,
                  DerivedRelationReference reference))
          || !(on
              instanceof
              ComparisonPredicate<?>(
                  SqlExpression<?> left,
                  ComparisonOperator operator,
                  SqlExpression<?> right))
          || operator != ComparisonOperator.EQUAL)
        throw StructuredWrites.invalid(
            "expectedVersion requires a single VALUES row matched by primary-key equality");
      var id = table.entity().primaryKey().orElseThrow().properties().getFirst();
      var target = MutationQuerySupport.table(table);
      int sourceOrdinal;
      if (left instanceof ColumnExpression<?, ?> column
          && column.table() == target
          && column.property() == id
          && right
              instanceof
              DerivedColumnExpression<?>(DerivedRelationReference relation, int outputOrdinal)
          && relation == reference) sourceOrdinal = outputOrdinal;
      else if (right instanceof ColumnExpression<?, ?> column
          && column.table() == target
          && column.property() == id
          && left
              instanceof
              DerivedColumnExpression<?>(DerivedRelationReference relation, int outputOrdinal)
          && relation == reference) sourceOrdinal = outputOrdinal;
      else
        throw StructuredWrites.invalid(
            "expectedVersion requires its target primary key and the same VALUES source occurrence");
      if (parameters.get(sourceOrdinal).nullability().isNullable())
        throw StructuredWrites.invalid("expectedVersion requires a non-null source primary key");
      for (MergeAction action : actions)
        if (action.kind() == MergeAction.Kind.INSERT) {
          int position = -1;
          for (int index = 0; index < action.columns().size(); index++)
            if (action.columns().get(index).property() == id) {
              position = index;
              break;
            }
          if (position < 0
              || !(action.values().get(position)
                  instanceof
                  DerivedColumnExpression<?>(DerivedRelationReference relation, int outputOrdinal))
              || relation != reference
              || outputOrdinal != sourceOrdinal)
            throw StructuredWrites.invalid(
                "strict MERGE INSERT must preserve the matched source primary key");
        }
    }

    private void validateInsertKey(List<ColumnExpression<?, ?>> columns) {
      var key = table.entity().primaryKey().orElseThrow();
      boolean supplied =
          columns.stream().anyMatch(column -> column.property() == key.properties().getFirst());
      if ((key.generation() == IdGeneration.IDENTITY) == supplied)
        throw StructuredWrites.invalid(
            "MERGE INSERT must obey its explicit primary-key source contract");
    }

    private boolean checking() {
      return draft.expected() != null || draft.expectedExpression() != null;
    }

    @Override
    void completed(int rows) {
      if (checking() && rows == 0) throw OptimisticLockException.of("optimistic MERGE conflict");
      if (rows < 0 || checking() && rows > 1)
        throw MutationException.of(
            SkisErrorCode.MUTATION_UNEXPECTED_ROW_COUNT,
            "MERGE did not return an attributable exact row count");
    }
  }

  private static final class Collector implements ExpressionRow {
    private final QueryTable<?> target;
    private final BitSet assigned = new BitSet();
    final List<Selectable<?>> columns = new ArrayList<>(), values = new ArrayList<>();
    boolean open = true;

    Collector(QueryTable<?> target) {
      this.target = target;
    }

    @Override
    public <V> ExpressionRow value(NonNullSelectable<V> column, NonNullSelectable<V> value) {
      return value((Selectable<V>) column, value);
    }

    @Override
    public <V> ExpressionRow value(Selectable<V> column, Selectable<V> value) {
      if (!open)
        throw StructuredWrites.invalid("MERGE row collector is only valid during its callback");
      var property = MutationQuerySupport.column(target, column).property();
      if (!property.column().insertable() || assigned.get(property.ordinal()))
        throw StructuredWrites.invalid("duplicate or non-insertable MERGE target");
      assigned.set(property.ordinal());
      columns.add(column);
      values.add(Objects.requireNonNull(value));
      return this;
    }
  }
}
