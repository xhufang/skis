package io.skis.mutation.internal;

import io.skis.core.ExecutionOptions;
import io.skis.core.MutationException;
import io.skis.core.SkisErrorCode;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.mapping.spi.ParameterBinder;
import io.skis.metadata.IdGeneration;
import io.skis.metadata.PropertyMeta;
import io.skis.mutation.ConflictAction;
import io.skis.mutation.ConflictFiltered;
import io.skis.mutation.ConflictSet;
import io.skis.mutation.ConflictUpdate;
import io.skis.mutation.ExpressionRow;
import io.skis.mutation.InsertInto;
import io.skis.mutation.InsertSelect;
import io.skis.mutation.InsertWrite;
import io.skis.mutation.KeyWrite;
import io.skis.mutation.MergeInto;
import io.skis.mutation.MergeNotMatched;
import io.skis.mutation.MergeOn;
import io.skis.mutation.MergeWrite;
import io.skis.mutation.ReturningWrite;
import io.skis.mutation.SingleWrite;
import io.skis.mutation.ValueRow;
import io.skis.mutation.Written;
import io.skis.query.DerivedRelation;
import io.skis.query.NonNullSelectable;
import io.skis.query.QueryCondition;
import io.skis.query.QueryTable;
import io.skis.query.Selectable;
import io.skis.query.SingleColumnQuery;
import io.skis.query.Sql;
import io.skis.query.ValuesSource;
import io.skis.query.internal.MutationQuerySupport;
import io.skis.query.internal.WriteInputCompiler;
import io.skis.sql.ast.BooleanPredicate;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.ConflictInsertStatement;
import io.skis.sql.ast.DerivedColumnExpression;
import io.skis.sql.ast.DerivedRelationReference;
import io.skis.sql.ast.InsertStatement;
import io.skis.sql.ast.LiteralExpression;
import io.skis.sql.ast.MergeStatement;
import io.skis.sql.ast.ParameterRelationSource;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.StatementAst;
import io.skis.sql.ast.UpdateAssignment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Immutable invocation stages. Shared caches receive only detached structure and compiled code. */
public final class StructuredWrites {

  private StructuredWrites() {}

  public static <E> InsertSelect<E> selectColumns(
      InsertInto<E> stage, Selectable<?> first, Selectable<?>... remaining) {
    return InsertSelects.columns(stage, first, remaining);
  }

  static <E, R extends @Nullable Object> ReturningWrite<R> entityReturning(
      DefaultMutationOperations operations,
      QueryTable<E> table,
      E entity,
      MutationQuerySupport.Result<R> result) {
    Spec<E> spec = new Spec<>(operations, table);
    spec.entity = entity;
    return new Single<>(spec).outputs(result);
  }

  static <E> MergeInto<E> merge(DefaultMutationOperations operations, QueryTable<E> table) {
    return new MergeChoice<>(operations, table);
  }

  public static <E> InsertWrite<E> insert(InsertInto<E> stage, Consumer<ValueRow> values) {
    var owner = MutationRequests.insertOwner(stage);
    Spec<E> spec = new Spec<>(owner.operations(), owner.table());
    collectValues(spec, values);
    return new Single<>(spec);
  }

  static <E> ConflictAction<E> batchConflict(
      BatchWrites.Input<E> batch, Selectable<?> first, Selectable<?>[] remaining) {
    Spec<E> spec = new Spec<>(batch.operations, batch.table);
    spec.batch = batch;
    spec.conflict = true;
    spec.keys = keys(first, remaining);
    validateKeys(spec, spec.keys);
    return new Single<>(spec);
  }

  static <E> ConflictAction<E> conflict(
      DefaultMutationOperations operations,
      QueryTable<E> table,
      E entity,
      Selectable<?> first,
      Selectable<?>[] remaining) {
    Spec<E> spec = new Spec<>(operations, table);
    spec.entity = entity;
    spec.conflict = true;
    spec.keys = keys(first, remaining);
    validateKeys(spec, spec.keys);
    return new Single<>(spec);
  }

  private static <E> SingleWrite<E> key(Spec<E> current, Consumer<ValueRow> values) {
    Spec<E> spec = new Spec<>(current);
    collectValues(spec, values);
    if (spec.table.entity().version().isPresent())
      throw invalid("KEY does not accept versioned entities");
    var id = spec.table.entity().primaryKey().orElseThrow().properties().getFirst();
    for (Selectable<?> column : spec.keys)
      if (!spec.columns.contains(column)) throw invalid("KEY must name provided columns");
    boolean idProvided = spec.columns.stream().anyMatch(c -> property(spec, c) == id);
    boolean idKey = spec.keys.stream().anyMatch(c -> property(spec, c) == id);
    if (idProvided && !idKey)
      throw invalid("business KEY cannot overwrite a candidate primary key");
    for (Selectable<?> column : spec.columns) {
      var property = property(spec, column);
      if (property != id && !property.column().updatable())
        throw invalid("KEY cannot update a protected column");
    }
    return new Single<>(spec);
  }

  public static <E> MergeWrite<E> mergeInsert(
      MergeNotMatched<E> stage, Consumer<ExpressionRow> values) {
    return NativeMerges.insert(stage, values);
  }

  private static List<Selectable<?>> keys(Selectable<?> first, Selectable<?>[] remaining) {
    List<Selectable<?>> result = new ArrayList<>(remaining.length + 1);
    result.add(Objects.requireNonNull(first));
    result.addAll(Arrays.asList(remaining));
    return List.copyOf(result);
  }

  private static void collectValues(Spec<?> spec, Consumer<ValueRow> values) {
    ValueCollector row = new ValueCollector(spec);
    try {
      Objects.requireNonNull(values, "values").accept(row);
      row.requireNonEmpty();
      spec.columns = List.copyOf(row.columns);
      spec.values = row.values.toArray();
    } finally {
      row.open = false;
    }
  }

  private abstract static class ColumnCollector {
    final Spec<?> spec;
    final List<Selectable<?>> columns = new ArrayList<>();
    private final BitSet seen = new BitSet();
    boolean open = true;

    ColumnCollector(Spec<?> spec) {
      this.spec = spec;
    }

    final void requireOpen() {
      if (!open) throw invalid("row collector is only valid during its callback");
    }

    final void add(Selectable<?> column) {
      requireOpen();
      var property = property(spec, column);
      if (!property.column().insertable() || seen.get(property.ordinal()))
        throw invalid("duplicate or non-insertable target column");
      seen.set(property.ordinal());
      columns.add(column);
    }

    final void requireNonEmpty() {
      if (columns.isEmpty()) throw invalid("at least one physical column is required");
    }

    @Override
    public final String toString() {
      return "WriteRowCollector[values redacted]";
    }
  }

  private static final class ValueCollector extends ColumnCollector implements ValueRow {
    final List<@Nullable Object> values = new ArrayList<>();

    ValueCollector(Spec<?> spec) {
      super(spec);
    }

    @Override
    public <V> ValueRow value(NonNullSelectable<V> column, V value) {
      return value((Selectable<V>) column, Objects.requireNonNull(value, "value"));
    }

    @Override
    public <V> ValueRow value(Selectable<V> column, @Nullable V value) {
      requireOpen();
      Object captured;
      try {
        captured = MutationQuerySupport.captureValue(column, value);
      } catch (IllegalArgumentException failure) {
        throw MutationException.of(
            SkisErrorCode.MUTATION_PARAMETER_SHAPE,
            "VALUES input violates its declared type or nullability",
            failure);
      }
      add(column);
      values.add(captured);
      return this;
    }
  }

  private static PropertyMeta<?, ?> property(Spec<?> spec, Selectable<?> column) {
    return MutationQuerySupport.column(spec.table, column).property();
  }

  private static void validateKeys(Spec<?> spec, List<Selectable<?>> columns) {
    if (columns.isEmpty()) throw invalid("at least one physical column is required");
    Set<Integer> seen = new HashSet<>();
    for (Selectable<?> column : columns) {
      var property = property(spec, column);
      if (!seen.add(property.ordinal())) throw invalid("duplicate key column");
    }
  }

  private static <E> Spec<E> assignment(
      Spec<E> current,
      Selectable<?> column,
      @Nullable Object value,
      @Nullable Selectable<?> expression) {
    if (current.nothing || !current.conflict)
      throw invalid("SET requires a conflict or MERGE update action");
    var property = property(current, column);
    if (!property.column().updatable()
        || current.table.entity().primaryKey().orElseThrow().properties().contains(property)
        || current.table.entity().version().map(v -> v.property() == property).orElse(false))
      throw invalid("SET cannot change primary-key, version or non-updatable columns");
    for (var assignment : current.assignments)
      if (property(current, assignment.column()) == property) throw invalid("duplicate SET column");
    Spec<E> spec = new Spec<>(current);
    List<MutationQuerySupport.Assignment> assignments = new ArrayList<>(spec.assignments);
    assignments.add(new MutationQuerySupport.Assignment(column, value, expression));
    spec.assignments = List.copyOf(assignments);
    return spec;
  }

  private static <E> Spec<E> expected(
      Spec<E> current,
      @Nullable Number value,
      @Nullable NonNullSelectable<? extends Number> expression) {
    if (current.nothing || !current.conflict)
      throw invalid("expected version requires an updating conflict branch");
    if (current.expected != null || current.expectedExpression != null)
      throw invalid("expected version is already set");
    var version =
        current
            .table
            .entity()
            .version()
            .orElseThrow(() -> invalid("target has no version metadata"));
    if (value != null && !version.property().javaType().isInstance(value)
        || expression != null
            && (!expression.javaType().equals(version.property().javaType())
                || expression.nullability().isNullable()))
      throw invalid("expected version type or nullability does not match metadata");
    Spec<E> spec = new Spec<>(current);
    spec.expected = value;
    spec.expectedExpression = expression;
    return spec;
  }

  /** Shared precondition for attributing a skipped conflict update to its expected version. */
  static @Nullable String strictConflictViolation(Spec<?> spec) {
    if (spec.filter != null || spec.nothing || spec.assignments.isEmpty())
      return "strict optimistic writes cannot contain business filters or skip actions";
    var id = spec.table.entity().primaryKey().orElseThrow().properties().getFirst();
    if (spec.keys.size() != 1 || property(spec, spec.keys.getFirst()) != id)
      return "strict conflict update requires the single primary-key conflict target";
    return null;
  }

  static MutationException invalid(String detail) {
    return MutationException.of(SkisErrorCode.MUTATION_PARAMETER_SHAPE, detail);
  }

  static final class Spec<E> {

    final DefaultMutationOperations operations;
    final QueryTable<E> table;
    @Nullable E entity;
    BatchWrites.@Nullable Input<E> batch;
    private volatile ValuesBatches.@Nullable Planner<E, Object> batchCountPlanner;
    List<Selectable<?>> columns = List.of(), keys = List.of();
    @Nullable Object[] values = new @Nullable Object[0];
    @Nullable QueryCondition filter;
    List<MutationQuerySupport.Assignment> assignments = List.of();
    @Nullable Number expected;
    @Nullable NonNullSelectable<? extends Number> expectedExpression;
    boolean key, conflict, nothing;

    Spec(DefaultMutationOperations operations, QueryTable<E> table) {
      this.operations = operations;
      this.table = Objects.requireNonNull(table);
    }

    Spec(Spec<E> other) {
      this(other.operations, other.table);
      entity = other.entity;
      batch = other.batch;
      // The planner retains its owning Spec and compiled clauses; derivatives must rebuild it.
      batchCountPlanner = null;
      columns = other.columns;
      keys = other.keys;
      values = other.values;
      filter = other.filter;
      assignments = other.assignments;
      expected = other.expected;
      expectedExpression = other.expectedExpression;
      key = other.key;
      conflict = other.conflict;
      nothing = other.nothing;
    }

    ValuesBatches.Planner<E, Object> batchCountPlanner(BatchWrites.Input<E> input) {
      var planner = batchCountPlanner;
      if (planner != null) return planner;
      synchronized (this) {
        planner = batchCountPlanner;
        if (planner == null) {
          planner = new ValuesBatches.Planner<>(input, this, null);
          batchCountPlanner = planner;
        }
        return planner;
      }
    }
  }

  static final class Choices<R extends @Nullable Object> {
    private volatile @Nullable Prepared checkedInputs, uncheckedInputs;

    Prepared prepare(Request<?> request, boolean check, MutationQuerySupport.Result<R> result) {
      Prepared known = check ? checkedInputs : uncheckedInputs;
      if (known != null) return known;
      synchronized (this) {
        known = check ? checkedInputs : uncheckedInputs;
        if (known == null) {
          known = compileChecked(request.spec, check, result);
          if (check) checkedInputs = known;
          else uncheckedInputs = known;
        }
      }
      return known;
    }

    private volatile DynamicMutationPlans.@Nullable Request<DynamicMutationPlans.StructuredPlan<R>>
        checked,
        unchecked;

    DynamicMutationPlans.Request<DynamicMutationPlans.StructuredPlan<R>> one(boolean check) {
      var value = check ? checked : unchecked;
      if (value != null) return value;
      synchronized (this) {
        value = check ? checked : unchecked;
        if (value == null) {
          value = new DynamicMutationPlans.Request<>();
          if (check) checked = value;
          else unchecked = value;
        }
      }
      return value;
    }
  }

  static final class Request<E> {
    final Spec<E> spec;
    private volatile DynamicMutationPlans.@Nullable Request<
            DynamicMutationPlans.StructuredPlan<Object>>
        checkedCount,
        uncheckedCount;
    private volatile @Nullable Prepared checked, unchecked;

    Request(Spec<E> spec) {
      this.spec = spec;
    }

    DynamicMutationPlans.Request<DynamicMutationPlans.StructuredPlan<Object>> count(boolean check) {
      var value = check ? checkedCount : uncheckedCount;
      if (value != null) return value;
      synchronized (this) {
        value = check ? checkedCount : uncheckedCount;
        if (value == null) {
          value = new DynamicMutationPlans.Request<>();
          if (check) checkedCount = value;
          else uncheckedCount = value;
        }
      }
      return value;
    }

    @SuppressWarnings("unchecked")
    @Nullable Object expectedValue() {
      // A plain entity INSERT has no matched row, so its version is an initial value, not a check.
      if (spec.nothing || !spec.conflict && !spec.key) return null;
      if (spec.expected != null) return spec.expected;
      if (spec.expectedExpression != null) return Boolean.TRUE;
      E entity = spec.entity;
      if (entity == null) return null;
      if (!spec.table.entity().javaType().isInstance(entity))
        throw MutationException.of(
            SkisErrorCode.MUTATION_ENTITY_TYPE_MISMATCH,
            "structured write entity type does not match its target");
      Prepared known = checked;
      if (known == null) known = unchecked;
      EntityMutationPlanSet<E> plans =
          known == null
              ? spec.operations.plans(spec.table)
              : (EntityMutationPlanSet<E>) Objects.requireNonNull(known.entityPlans());
      return plans.readExpectedVersion(entity);
    }

    Prepared prepare(boolean check) {
      Prepared existing = check ? checked : unchecked;
      if (existing != null) return existing;
      synchronized (this) {
        existing = check ? checked : unchecked;
        if (existing == null) {
          existing = compileChecked(spec, check, null);
          if (check) checked = existing;
          else unchecked = existing;
        }
      }
      return existing;
    }
  }

  record Arguments(@Nullable Object base, @Nullable Object[] values, @Nullable Object originalId) {
    Arguments(@Nullable Object base, @Nullable Object[] values) {
      this(base, values, null);
    }

    @Override
    public String toString() {
      return "WriteArguments[values redacted]";
    }
  }

  record Prepared(
      ResolvedStatement resolved,
      MutationQuerySupport.Inputs inputs,
      @Nullable ParameterBinder<?> prefix,
      int prefixCount,
      Object prefixIdentity,
      @Nullable EntityMutationPlanSet<?> entityPlans,
      ColumnExpression<?, ?> idColumn,
      boolean checked,
      int expectedIndex,
      Arguments fixedArguments,
      String operation,
      int parameterBudget) {}

  private static Prepared compileChecked(
      Spec<?> spec, boolean checked, MutationQuerySupport.@Nullable Result<?> returning) {
    try {
      return compile(spec, checked, returning);
    } catch (IllegalArgumentException failure) {
      throw MutationException.of(
          SkisErrorCode.MUTATION_PARAMETER_SHAPE,
          "structured write has invalid clauses or parameters",
          failure);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Prepared compile(
      Spec<?> spec, boolean checked, MutationQuerySupport.@Nullable Result<?> returning) {
    EntityMutationPlanSet<?> targetPlans = spec.operations.plans(spec.table);
    EntityMutationPlanSet<?> plans = spec.entity == null ? null : targetPlans;
    var target = MutationQuerySupport.table(spec.table);
    int prefixCount = plans == null ? 0 : plans.insertStatement().values().size();
    WriteInputCompiler compiler =
        new WriteInputCompiler(spec.operations.dynamicPlans().registry(), spec.table, prefixCount);
    ParameterBinder<?> prefix =
        plans == null
            ? null
            : plans.generation() == IdGeneration.ASSIGNED
                ? plans.insert().parameterBinder()
                : plans.generatedInsert().parameterBinder();
    var idProperty = spec.table.entity().primaryKey().orElseThrow().properties().getFirst();
    ColumnExpression<?, ?> idColumn = compiler.column(idProperty);
    List<ColumnExpression<?, ?>> insertColumns;
    List<SqlExpression<?>> insertValues;
    if (plans != null) {
      insertColumns = new ArrayList<>();
      for (var column : plans.insertStatement().columns())
        insertColumns.add(compiler.column(column.property()));
      insertValues = new ArrayList<>(plans.insertStatement().values());
    } else {
      insertColumns = new ArrayList<>(compiler.columns(spec.columns));
      insertValues = new ArrayList<>(compiler.values(spec.columns, spec.values));
    }
    if (plans == null) {
      boolean suppliedId = insertColumns.stream().anyMatch(c -> c.property() == idProperty);
      IdGeneration generation = spec.table.entity().primaryKey().orElseThrow().generation();
      if (generation == IdGeneration.IDENTITY && suppliedId)
        throw invalid("IDENTITY insertion cannot supply a primary key");
      if (generation != IdGeneration.IDENTITY && !suppliedId)
        throw invalid("explicit value sources must provide their application primary key");
      var version = spec.table.entity().version().orElse(null);
      if (version != null
          && insertColumns.stream().noneMatch(c -> c.property() == version.property())) {
        ColumnExpression column = compiler.column(version.property());
        insertColumns.add(column);
        insertValues.add(LiteralExpression.zero(column.javaType()));
      }
    }
    List<UpdateAssignment<?>> assignments =
        compiler.assignments(spec.assignments, !spec.assignments.isEmpty());
    SqlPredicate condition = compiler.condition(spec.filter);
    int expectedIndex = -1;
    if (checked) {
      var version =
          spec.table
              .entity()
              .version()
              .orElseThrow(() -> invalid("target has no version metadata"));
      String violation = strictConflictViolation(spec);
      if (violation != null) throw invalid(violation);
      ColumnExpression versionColumn = compiler.column(version.property());
      SqlExpression expected;
      if (spec.expectedExpression != null) expected = compiler.expression(spec.expectedExpression);
      else {
        Selectable<?> template = compiler.selectable(version.property());
        Object initial =
            spec.expected == null
                ? version.strategy().initialize((Class) version.property().javaType(), null)
                : spec.expected;
        expected = compiler.values(List.of(template), new Object[] {initial}).getFirst();
        expectedIndex = ((ParameterSlot<?>) expected).ordinal() - prefixCount;
      }
      condition = new ComparisonPredicate(versionColumn, ComparisonOperator.EQUAL, expected);
    }
    StatementAst statement;
    if (spec.key) {
      statement =
          new MergeStatement(
              target,
              null,
              new BooleanPredicate(true),
              List.of(),
              insertColumns,
              insertValues,
              compiler.columns(spec.keys));
    } else {
      var insert = new InsertStatement(target, insertColumns, insertValues);
      statement =
          spec.conflict
              ? new ConflictInsertStatement(
                  insert, compiler.columns(spec.keys), assignments, condition, spec.nothing)
              : insert;
    }
    // Validate this occurrence before any shared lookup; structure equality cannot prove scope.
    ResolvedStatement resolved = ResolvedStatement.analyze(statement);
    compiler.returning(returning);
    MutationQuerySupport.Inputs inputs = compiler.finish();
    if (plans == null) validateInsertId(targetPlans, insertColumns, insertValues, null, inputs);
    inputs =
        new MutationQuerySupport.Inputs(
            inputs.assignments(),
            inputs.where(),
            inputs.codecs(),
            inputs
                .cacheIdentity()
                .map(
                    identity -> {
                      List<Object> shape = new ArrayList<>(identity);
                      shape.add(checked);
                      return List.copyOf(shape);
                    }),
            inputs.values(),
            inputs.sources(),
            inputs.returningExpressions());
    return new Prepared(
        resolved,
        inputs,
        prefix,
        prefixCount,
        plans == null ? Boolean.FALSE : plans.structuredIdentity(),
        plans,
        idColumn,
        checked,
        expectedIndex,
        new Arguments(spec.entity, inputs.values()),
        spec.key ? "merge" : "insert",
        0);
  }

  static void validateInsertId(
      EntityMutationPlanSet<?> plans,
      List<ColumnExpression<?, ?>> columns,
      List<SqlExpression<?>> values,
      @Nullable ParameterRelationSource source,
      MutationQuerySupport.Inputs inputs) {
    if (plans.generation() == IdGeneration.IDENTITY) return;
    for (int index = 0; index < columns.size(); index++) {
      if (columns.get(index).property() != plans.idProperty()) continue;
      SqlExpression<?> value = values.get(index);
      if (value
              instanceof
              DerivedColumnExpression<?>(DerivedRelationReference relation, int outputOrdinal)
          && source != null
          && relation == source.reference()) {
        value = source.parameters().get(outputOrdinal);
      }
      if (value instanceof ParameterSlot<?> parameter) {
        DefaultMutationOperations.validateId(plans, inputs.values()[parameter.ordinal()]);
      } else if (plans.generation() == IdGeneration.SNOWFLAKE
          || plans.generation() == IdGeneration.UUID
              && plans.idProperty().javaType() == String.class) {
        throw invalid(
            "UUID text and snowflake insertion keys require a captured value or an owned VALUES column");
      }
      return;
    }
  }

  private static final class Returning<R extends @Nullable Object> implements ReturningWrite<R> {
    private final Function<ExecutionOptions, Written<R>> write;

    Returning(Function<ExecutionOptions, Written<R>> write) {
      this.write = write;
    }

    @Override
    public Written<R> write() {
      return write(ExecutionOptions.NONE);
    }

    @Override
    public Written<R> write(ExecutionOptions options) {
      return write.apply(Objects.requireNonNull(options));
    }

    @Override
    public String toString() {
      return "ReturningWrite[values redacted]";
    }
  }

  private abstract static class Stage<E> extends WriteSelections<E> {
    final Spec<E> spec;
    private volatile @Nullable Request<E> request;

    Stage(Spec<E> spec) {
      super(spec.operations, spec.table);
      this.spec = spec;
    }

    final Request<E> request() {
      Request<E> value = request;
      if (value != null) return value;
      synchronized (this) {
        value = request;
        if (value == null) {
          value = new Request<>(spec);
          request = value;
        }
      }
      return value;
    }
  }

  private static final class Single<E> extends Stage<E>
      implements InsertWrite<E>, ConflictAction<E>, ConflictUpdate<E>, ConflictFiltered<E> {
    Single(Spec<E> spec) {
      super(spec);
    }

    @Override
    public ConflictAction<E> onConflict(Selectable<?> first, Selectable<?>... remaining) {
      if (spec.conflict || spec.key) throw invalid("duplicate conflict clause");
      Spec<E> next = new Spec<>(spec);
      next.conflict = true;
      next.keys = keys(first, remaining);
      validateKeys(next, next.keys);
      return new Single<>(next);
    }

    @Override
    public SingleWrite<E> doNothing() {
      Spec<E> next = new Spec<>(spec);
      next.nothing = true;
      return new Single<>(next);
    }

    @Override
    public ConflictSet<E> doUpdate() {
      return this;
    }

    @Override
    public <V> ConflictUpdate<E> set(NonNullSelectable<V> column, V value) {
      return set((Selectable<V>) column, Objects.requireNonNull(value));
    }

    @Override
    public <V> ConflictUpdate<E> set(Selectable<V> column, @Nullable V value) {
      return new Single<>(assignment(spec, column, value, null));
    }

    @Override
    public <V> ConflictUpdate<E> set(NonNullSelectable<V> column, NonNullSelectable<V> value) {
      return set((Selectable<V>) column, value);
    }

    @Override
    public <V> ConflictUpdate<E> set(Selectable<V> column, Selectable<V> value) {
      return new Single<>(assignment(spec, column, null, Objects.requireNonNull(value)));
    }

    @Override
    public <V> ConflictUpdate<E> set(
        Selectable<V> column, SingleColumnQuery<? extends @Nullable V> query) {
      return set(column, Sql.scalar(query));
    }

    @Override
    public <V> ConflictUpdate<E> setNull(Selectable<V> column) {
      MutationQuerySupport.Assignment input = MutationQuerySupport.setNull(column);
      return new Single<>(assignment(spec, column, null, input.expression()));
    }

    @Override
    public <V> ConflictUpdate<E> setDefault(Selectable<V> column) {
      MutationQuerySupport.Assignment input = MutationQuerySupport.setDefault(column);
      return new Single<>(assignment(spec, column, null, input.expression()));
    }

    @Override
    public ConflictUpdate<E> expectedVersion(Number value) {
      return new Single<>(expected(spec, Objects.requireNonNull(value), null));
    }

    @Override
    public ConflictUpdate<E> expectedVersionExpression(NonNullSelectable<? extends Number> value) {
      return new Single<>(expected(spec, null, Objects.requireNonNull(value)));
    }

    @Override
    public Single<E> where(QueryCondition condition) {
      if (spec.filter != null) throw invalid("WHERE is already set");
      Spec<E> next = new Spec<>(spec);
      next.filter = Objects.requireNonNull(condition);
      return new Single<>(next);
    }

    @Override
    public Single<E> and(QueryCondition condition) {
      Spec<E> next = new Spec<>(spec);
      next.filter = Objects.requireNonNull(spec.filter).and(condition);
      return new Single<>(next);
    }

    @Override
    public Single<E> or(QueryCondition condition) {
      Spec<E> next = new Spec<>(spec);
      next.filter = Objects.requireNonNull(spec.filter).or(condition);
      return new Single<>(next);
    }

    @Override
    public int write() {
      return write(ExecutionOptions.NONE);
    }

    @Override
    public int write(ExecutionOptions options) {
      return spec.batch != null
          ? BatchWrites.count(spec.batch, spec, Objects.requireNonNull(options))
          : spec.operations.writeStructuredCount(request(), Objects.requireNonNull(options));
    }

    @Override
    @SuppressWarnings("Convert2Diamond")
    <R extends @Nullable Object> ReturningWrite<R> outputs(MutationQuerySupport.Result<R> result) {
      if (spec.batch != null) return BatchWrites.outputsFor(spec.batch, spec, result);
      Choices<R> choices = new Choices<>();
      Request<E> operation = request();
      return new Returning<R>(
          options -> spec.operations.writeStructuredOne(operation, result, choices, options));
    }

    @Override
    public String toString() {
      return "StructuredSingleWrite[values redacted]";
    }
  }

  private record MergeChoice<E>(DefaultMutationOperations operations, QueryTable<E> table)
      implements MergeInto<E> {
    @Override
    public MergeOn<E> using(ValuesSource source) {
      return NativeMerges.using(operations, table, source);
    }

    @Override
    public MergeOn<E> using(QueryTable<?> source) {
      return NativeMerges.using(operations, table, source);
    }

    @Override
    public MergeOn<E> using(DerivedRelation source) {
      return NativeMerges.using(operations, table, source);
    }

    @Override
    public KeyWrite<E> key(Selectable<?> first, Selectable<?>... remaining) {
      Spec<E> spec = new Spec<>(operations, table);
      spec.key = true;
      spec.keys = keys(first, remaining);
      validateKeys(spec, spec.keys);
      return values -> StructuredWrites.key(spec, values);
    }
  }
}
