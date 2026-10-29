package io.skis.mutation.internal;

import io.skis.core.*;
import io.skis.dialect.spi.ResolvedStatement;
import io.skis.mapping.spi.ParameterBinder;
import io.skis.metadata.*;
import io.skis.mutation.*;
import io.skis.query.*;
import io.skis.query.internal.MutationQuerySupport;
import io.skis.query.internal.WriteInputCompiler;
import io.skis.sql.ast.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Immutable invocation stages. Shared caches receive only detached structure and compiled code. */
public final class StructuredWrites {
  private StructuredWrites() {}

  static <E> MergeInto<E> merge(DefaultMutationOperations operations, QueryTable<E> table) {
    Spec<E> spec = new Spec<>(operations, table);
    spec.merge = true;
    return new Merge<>(spec);
  }

  public static <E> InsertWrite<E> insert(InsertInto<E> stage, Consumer<ValueRow> values) {
    var owner = MutationRequests.insertOwner(stage);
    Spec<E> spec = new Spec<>(owner.operations(), owner.table());
    collectValues(spec, values);
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
    if (!(stage instanceof Merge<E> merge)) throw invalid("MERGE stage was not created by SKIS");
    Spec<E> spec = new Spec<>(merge.spec);
    if (!spec.insertStarted || !spec.insertColumns.isEmpty())
      throw invalid("duplicate MERGE insertion");
    ExpressionCollector row = new ExpressionCollector(spec);
    try {
      Objects.requireNonNull(values, "values").accept(row);
      row.requireNonEmpty();
      spec.insertColumns = List.copyOf(row.columns);
      spec.insertExpressions = List.copyOf(row.expressions);
    } finally {
      row.open = false;
    }
    return new Merge<>(spec);
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

  private static final class ExpressionCollector extends ColumnCollector implements ExpressionRow {
    final List<Selectable<?>> expressions = new ArrayList<>();

    ExpressionCollector(Spec<?> spec) {
      super(spec);
    }

    @Override
    public <V> ExpressionRow value(NonNullSelectable<V> column, NonNullSelectable<V> value) {
      return value((Selectable<V>) column, value);
    }

    @Override
    public <V> ExpressionRow value(Selectable<V> column, Selectable<V> value) {
      requireOpen();
      Objects.requireNonNull(value, "expression");
      if (!column.javaType().equals(value.javaType())
          || !column.nullability().isNullable() && value.nullability().isNullable())
        throw invalid("INSERT expression violates its declared type or nullability");
      add(column);
      expressions.add(value);
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
    if (current.nothing || current.key || !current.merge && !current.conflict)
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
    if (current.nothing || current.key || !current.merge && !current.conflict)
      throw invalid("expected version requires an updating conflict or MERGE branch");
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

  static MutationException invalid(String detail) {
    return MutationException.of(SkisErrorCode.MUTATION_PARAMETER_SHAPE, detail);
  }

  static final class Spec<E> {
    final DefaultMutationOperations operations;
    final QueryTable<E> table;
    @Nullable E entity;
    List<Selectable<?>> columns = List.of(),
        keys = List.of(),
        insertColumns = List.of(),
        insertExpressions = List.of();
    @Nullable Object[] values = new @Nullable Object[0];
    @Nullable ValuesSource source;
    @Nullable QueryCondition on, matched, filter;
    List<MutationQuerySupport.Assignment> assignments = List.of();
    @Nullable Number expected;
    @Nullable NonNullSelectable<? extends Number> expectedExpression;
    boolean merge, key, conflict, nothing, matchedStarted, insertStarted;

    Spec(DefaultMutationOperations operations, QueryTable<E> table) {
      this.operations = operations;
      this.table = Objects.requireNonNull(table);
    }

    Spec(Spec<E> other) {
      this(other.operations, other.table);
      entity = other.entity;
      columns = other.columns;
      keys = other.keys;
      insertColumns = other.insertColumns;
      insertExpressions = other.insertExpressions;
      values = other.values;
      source = other.source;
      on = other.on;
      matched = other.matched;
      filter = other.filter;
      assignments = other.assignments;
      expected = other.expected;
      expectedExpression = other.expectedExpression;
      merge = other.merge;
      key = other.key;
      conflict = other.conflict;
      nothing = other.nothing;
      matchedStarted = other.matchedStarted;
      insertStarted = other.insertStarted;
    }
  }

  static final class Choices<R> {
    private volatile DynamicMutationPlans.@Nullable Request<DynamicMutationPlans.StructuredPlan<R>>
        checked,
        unchecked;
    private volatile DynamicMutationPlans.@Nullable Request<DynamicMutationPlans.ConditionalPlan<R>>
        manyChecked,
        manyUnchecked;

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

    DynamicMutationPlans.Request<DynamicMutationPlans.ConditionalPlan<R>> many(boolean check) {
      var value = check ? manyChecked : manyUnchecked;
      if (value != null) return value;
      synchronized (this) {
        value = check ? manyChecked : manyUnchecked;
        if (value == null) {
          value = new DynamicMutationPlans.Request<>();
          if (check) manyChecked = value;
          else manyUnchecked = value;
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
      if (spec.nothing) return null;
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
          try {
            existing = compile(spec, check);
          } catch (IllegalArgumentException failure) {
            throw MutationException.of(
                SkisErrorCode.MUTATION_PARAMETER_SHAPE,
                "structured write has invalid clauses or parameters",
                failure);
          }
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
      boolean merge,
      boolean single,
      boolean checked,
      int expectedIndex,
      Arguments fixedArguments) {}

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Prepared compile(Spec<?> spec, boolean checked) {
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
    ParameterRelationSource source = null;
    SqlPredicate on = new BooleanPredicate(true);
    if (spec.merge && !spec.key) {
      source = compiler.source(Objects.requireNonNull(spec.source));
      on = compiler.condition(Objects.requireNonNull(spec.on));
      insertColumns = new ArrayList<>(compiler.columns(spec.insertColumns));
      insertValues = new ArrayList<>();
      for (Selectable<?> value : spec.insertExpressions)
        insertValues.add(compiler.expression(value));
    } else if (plans != null) {
      insertColumns = new ArrayList<>();
      for (var column : plans.insertStatement().columns())
        insertColumns.add(compiler.column(column.property()));
      insertValues = new ArrayList<>(plans.insertStatement().values());
    } else {
      insertColumns = new ArrayList<>(compiler.columns(spec.columns));
      insertValues = new ArrayList<>(compiler.values(spec.columns, spec.values));
    }
    if (plans == null && (!spec.merge || spec.key || !insertColumns.isEmpty())) {
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
    SqlPredicate condition = compiler.condition(spec.merge ? spec.matched : spec.filter);
    int expectedIndex = -1;
    if (checked) {
      var version =
          spec.table
              .entity()
              .version()
              .orElseThrow(() -> invalid("target has no version metadata"));
      if (spec.filter != null || spec.matched != null || assignments.isEmpty())
        throw invalid("strict optimistic writes cannot contain business filters or skip actions");
      if (spec.merge) {
        if (!(on
                instanceof
                ComparisonPredicate<?>(
                    ColumnExpression<?, ?> left,
                    ComparisonOperator operator,
                    DerivedColumnExpression<?> right))
            || operator != ComparisonOperator.EQUAL
            || left.property() != idProperty
            || left.table() != target
            || right.relation() != Objects.requireNonNull(source).reference()
            || right.nullable()
            || insertColumns.stream().noneMatch(c -> c.property() == idProperty))
          throw invalid(
              "strict MERGE requires primary-key equality to a non-null source and an INSERT branch");
        int idIndex = -1;
        for (int i = 0; i < insertColumns.size(); i++)
          if (insertColumns.get(i).property() == idProperty) idIndex = i;
        if (idIndex < 0 || !insertValues.get(idIndex).equals(right))
          throw invalid("strict MERGE insertion must use the matched source primary key");
      } else if (spec.keys.size() != 1 || property(spec, spec.keys.getFirst()) != idProperty) {
        throw invalid("strict conflict update requires the single primary-key conflict target");
      }
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
    if (spec.merge) {
      statement =
          new MergeStatement(
              target,
              source,
              on,
              condition,
              assignments,
              insertColumns,
              insertValues,
              spec.key ? compiler.columns(spec.keys) : List.of());
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
    MutationQuerySupport.Inputs inputs = compiler.finish();
    if (plans == null) validateInsertId(targetPlans, insertColumns, insertValues, source, inputs);
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
            inputs.values());
    return new Prepared(
        resolved,
        inputs,
        prefix,
        prefixCount,
        plans == null ? Boolean.FALSE : plans.structuredIdentity(),
        plans,
        idColumn,
        spec.merge,
        !spec.merge || spec.key,
        checked,
        expectedIndex,
        new Arguments(spec.entity, inputs.values()));
  }

  private static void validateInsertId(
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

  private static final class Returning<R> implements ReturningWrite<R> {
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

  private abstract static class Stage<E> {
    final Spec<E> spec;
    private volatile @Nullable Request<E> request;

    Stage(Spec<E> spec) {
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
      if (spec.conflict || spec.merge) throw invalid("duplicate conflict clause");
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
    public <V> ConflictUpdate<E> setExpression(
        NonNullSelectable<V> column, NonNullSelectable<V> value) {
      return setExpression((Selectable<V>) column, value);
    }

    @Override
    public <V> ConflictUpdate<E> setExpression(Selectable<V> column, Selectable<V> value) {
      return new Single<>(assignment(spec, column, null, Objects.requireNonNull(value)));
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
      return spec.operations.writeStructuredCount(request(), Objects.requireNonNull(options));
    }

    @Override
    public ReturningWrite<E> returning() {
      return returning(MutationQuerySupport.result(spec.table));
    }

    @Override
    public <R> ReturningWrite<R> returning(ProjectionSelection<R> projection) {
      return returning(MutationQuerySupport.result(spec.table, projection));
    }

    @Override
    public <R> ReturningWrite<R> returning(ProjectionType<R> projection) {
      return returning(MutationQuerySupport.result(spec.table, projection));
    }

    private <R> ReturningWrite<R> returning(MutationQuerySupport.Result<R> result) {
      Choices<R> choices = new Choices<>();
      Request<E> operation = request();
      return new Returning<>(
          options -> spec.operations.writeStructuredOne(operation, result, choices, options));
    }

    @Override
    public String toString() {
      return "StructuredSingleWrite[values redacted]";
    }
  }

  private static final class Merge<E> extends Stage<E>
      implements MergeInto<E>,
          MergeOn<E>,
          MergeActions<E>,
          MergeMatched<E>,
          MergeUpdate<E>,
          MergeNotMatched<E> {
    Merge(Spec<E> spec) {
      super(spec);
    }

    @Override
    public KeyWrite<E> key(Selectable<?> first, Selectable<?>... remaining) {
      Spec<E> next = new Spec<>(spec);
      next.key = true;
      next.keys = keys(first, remaining);
      validateKeys(next, next.keys);
      return values -> StructuredWrites.key(next, values);
    }

    @Override
    public MergeOn<E> using(ValuesSource source) {
      Spec<E> next = new Spec<>(spec);
      next.source = Objects.requireNonNull(source);
      return new Merge<>(next);
    }

    @Override
    public MergeActions<E> on(QueryCondition condition) {
      Spec<E> next = new Spec<>(spec);
      next.on = Objects.requireNonNull(condition);
      return new Merge<>(next);
    }

    @Override
    public MergeMatched<E> whenMatched() {
      if (spec.matchedStarted || spec.insertStarted)
        throw invalid("duplicate or out-of-order MATCHED branch");
      Spec<E> next = new Spec<>(spec);
      next.matchedStarted = true;
      return new Merge<>(next);
    }

    @Override
    public MergeNotMatched<E> whenNotMatched() {
      if (spec.insertStarted) throw invalid("duplicate NOT MATCHED branch");
      Spec<E> next = new Spec<>(spec);
      next.insertStarted = true;
      return new Merge<>(next);
    }

    @Override
    public MergeMatched<E> and(QueryCondition condition) {
      Spec<E> next = new Spec<>(spec);
      next.matched =
          spec.matched == null ? Objects.requireNonNull(condition) : spec.matched.and(condition);
      return new Merge<>(next);
    }

    @Override
    public MergeUpdateSet<E> thenUpdate() {
      return this;
    }

    @Override
    public <V> MergeUpdate<E> set(NonNullSelectable<V> column, V value) {
      return set((Selectable<V>) column, Objects.requireNonNull(value));
    }

    @Override
    public <V> MergeUpdate<E> set(Selectable<V> column, @Nullable V value) {
      return new Merge<>(assignment(spec, column, value, null));
    }

    @Override
    public <V> MergeUpdate<E> setExpression(
        NonNullSelectable<V> column, NonNullSelectable<V> value) {
      return setExpression((Selectable<V>) column, value);
    }

    @Override
    public <V> MergeUpdate<E> setExpression(Selectable<V> column, Selectable<V> value) {
      return new Merge<>(assignment(spec, column, null, Objects.requireNonNull(value)));
    }

    @Override
    public MergeUpdate<E> expectedVersion(Number value) {
      return new Merge<>(expected(spec, Objects.requireNonNull(value), null));
    }

    @Override
    public MergeUpdate<E> expectedVersionExpression(NonNullSelectable<? extends Number> value) {
      return new Merge<>(expected(spec, null, Objects.requireNonNull(value)));
    }

    @Override
    public int write() {
      return write(ExecutionOptions.NONE);
    }

    @Override
    public int write(ExecutionOptions options) {
      return spec.operations.writeStructuredCount(request(), Objects.requireNonNull(options));
    }

    @Override
    public ReturningWrite<List<E>> returning() {
      return returning(MutationQuerySupport.result(spec.table));
    }

    @Override
    public <R> ReturningWrite<List<R>> returning(ProjectionSelection<R> projection) {
      return returning(MutationQuerySupport.result(spec.table, projection));
    }

    @Override
    public <R> ReturningWrite<List<R>> returning(ProjectionType<R> projection) {
      return returning(MutationQuerySupport.result(spec.table, projection));
    }

    private <R> ReturningWrite<List<R>> returning(MutationQuerySupport.Result<R> result) {
      Choices<R> choices = new Choices<>();
      Request<E> operation = request();
      return new Returning<>(
          options -> spec.operations.writeStructuredMany(operation, result, choices, options));
    }

    @Override
    public String toString() {
      return "MergeWrite[values redacted]";
    }
  }
}
