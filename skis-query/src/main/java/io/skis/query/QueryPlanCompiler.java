package io.skis.query;

import io.skis.dialect.Dialect;
import io.skis.dialect.RenderedSql;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mapping.JdbcWriteContext;
import io.skis.mapping.PropertyRuntime;
import io.skis.mapping.RowDecoder;
import io.skis.mapping.RowReadContext;
import io.skis.metadata.PropertyMeta;
import io.skis.sql.ast.ColumnExpression;
import io.skis.sql.ast.ComparisonOperator;
import io.skis.sql.ast.ComparisonPredicate;
import io.skis.sql.ast.CountAst;
import io.skis.sql.ast.HiddenSelection;
import io.skis.sql.ast.Identifier;
import io.skis.sql.ast.InPredicate;
import io.skis.sql.ast.KeysetSeek;
import io.skis.sql.ast.Limit;
import io.skis.sql.ast.LogicalOperator;
import io.skis.sql.ast.LogicalPredicate;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.OffsetLimit;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectPagination;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SqlExpression;
import io.skis.sql.ast.SqlPredicate;
import io.skis.sql.ast.SqlType;
import io.skis.sql.ast.StatementAst;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Compiles immutable query shapes into value-independent JDBC plans. */
final class QueryPlanCompiler {

  private final Dialect dialect;
  private final EntityRuntimeRegistry runtimeRegistry;

  QueryPlanCompiler(EntityRuntimeRegistry runtimeRegistry, Dialect dialect) {
    this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
    this.dialect = Objects.requireNonNull(dialect, "dialect");
  }

  /** Compiles the no-predicate or one-property equality Fast Path. */
  <E> CompiledQueryPlan<E, Object> compile(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      @Nullable PropertyMeta<E, ?> equalityProperty) {
    requireCanonicalModel(model, table);
    PredicateShape<E> shape = equalityShape(table, equalityProperty);
    SelectStatement statement =
        validatedStatement(() -> new SelectStatement(table.selections(), table, shape.ast()));
    return compilePlanFromProperties(model, statement, shape.properties(), model.fullRowDecoder());
  }

  <E> CompiledQueryPlan<E, Object> compileQuery(
      EntityRuntimeModel<E> model, QueryTable<E> table, @Nullable QueryPredicate<E> predicate) {
    requireCanonicalModel(model, table);
    PredicateShape<E> shape = predicateShape(predicate);
    SelectStatement statement =
        validatedStatement(() -> new SelectStatement(table.selections(), table, shape.ast()));
    return compilePlanFromProperties(model, statement, shape.properties(), model.fullRowDecoder());
  }

  <E, R> CompiledQueryPlan<R, Object> compileProjection(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      Projection<E, R> projection,
      @Nullable QueryPredicate<E> predicate) {
    requireCanonicalModel(model, table);
    Selection<R> selection = projectionSelection(model, table, projection);
    PredicateShape<E> shape = predicateShape(predicate);
    SelectStatement statement =
        validatedStatement(() -> new SelectStatement(selection.expressions(), table, shape.ast()));
    return compilePlanFromProperties(model, statement, shape.properties(), selection.decoder());
  }

  <E, R> QueryCompilation<Long> compileCount(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      SelectedResult<?, R> selected,
      List<QueryJoin> joins,
      @Nullable QueryCondition condition,
      boolean distinct) {
    requireCanonicalModel(model, table);
    CompiledQueryStructure structure = QueryStructureCompiler.compile(table, joins, condition);
    TableRuntimeScope runtimeScope =
        TableRuntimeScope.resolve(runtimeRegistry, structure.fromClause());
    Selection<R> selection = selected.resolve(runtimeScope);
    return compileResolvedCount(model, structure, runtimeScope, selection, distinct);
  }

  private <E, R> QueryCompilation<Long> compileResolvedCount(
      EntityRuntimeModel<E> model,
      CompiledQueryStructure structure,
      TableRuntimeScope runtimeScope,
      Selection<R> selection,
      boolean distinct) {
    validatedStatement(
        () ->
            new SelectStatement(
                false,
                selection.expressions(),
                List.of(),
                structure.fromClause(),
                structure.where(),
                List.of(),
                null));
    CountAst count =
        validatedStatement(
            () ->
                new CountAst(
                    structure.fromClause(),
                    structure.where(),
                    countDistinctExpression(model, selection, distinct)));
    InputsBuilder<E> inputs = new InputsBuilder<>(runtimeScope, structure);
    CompiledQueryPlan<Long, Object> plan =
        compilePlan(
            model,
            count,
            inputs.logicalParameters(),
            (resultSet, context) -> {
              long value = resultSet.getLong(1);
              if (resultSet.wasNull()) {
                throw new SQLException("COUNT result was unexpectedly null");
              }
              return value;
            });
    return new QueryCompilation<>(plan, inputs.argument(), count);
  }

  private static <E, R> @Nullable SqlExpression<?> countDistinctExpression(
      EntityRuntimeModel<E> model, Selection<R> selection, boolean distinct) {
    if (!distinct || isCompleteEntitySelection(model, selection.expressions())) {
      return null;
    }
    if (selection.expressions().size() != 1) {
      throw new QueryValidationException(
          "automatic count cannot preserve a multi-expression distinct result; provide an explicit count query");
    }
    return selection.expressions().getFirst();
  }

  <E> Selection<E> entitySelection(EntityRuntimeModel<E> model, QueryTable<E> table) {
    requireCanonicalModel(model, table);
    return Selection.of(table.selections(), model.fullRowDecoder());
  }

  <E, R> Selection<R> projectionSelection(
      EntityRuntimeModel<E> model, QueryTable<E> table, Projection<E, R> projection) {
    Objects.requireNonNull(projection, "projection").validateFrom(table);
    List<QueryColumn<E, ?>> columns = projection.columns(table);
    List<SqlExpression<?>> selections = new ArrayList<>(columns.size());
    for (QueryColumn<E, ?> column : columns) {
      selections.add(column.expression());
    }
    return new Selection<>(selections, projection.rowDecoder(model));
  }

  <E, R> QueryCompilation<OrderedRow<R>> compileOrdered(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      SelectedResult<?, R> selected,
      List<QueryJoin> joins,
      @Nullable QueryCondition condition,
      List<SortSpecification<E>> orderBy,
      boolean distinct,
      QueryPagination pagination) {
    requireCanonicalModel(model, table);
    CompiledQueryStructure structure = QueryStructureCompiler.compile(table, joins, condition);
    TableRuntimeScope runtimeScope =
        TableRuntimeScope.resolve(runtimeRegistry, structure.fromClause());
    Selection<R> selection = selected.resolve(runtimeScope);
    return compileResolvedOrdered(
        model, structure, runtimeScope, selection, orderBy, distinct, pagination);
  }

  private <E, R> QueryCompilation<OrderedRow<R>> compileResolvedOrdered(
      EntityRuntimeModel<E> model,
      CompiledQueryStructure structure,
      TableRuntimeScope runtimeScope,
      Selection<R> selection,
      List<SortSpecification<E>> orderBy,
      boolean distinct,
      QueryPagination pagination) {
    List<HiddenSelection> hidden = new ArrayList<>();
    int[] indexes = new int[orderBy.size()];
    for (int index = 0; index < orderBy.size(); index++) {
      SqlExpression<?> expression = orderBy.get(index).column().expression();
      int visibleIndex = selection.expressions().indexOf(expression);
      if (visibleIndex >= 0) {
        indexes[index] = visibleIndex + 1;
      } else {
        if (distinct) {
          throw new QueryValidationException(
              "distinct keyset ordering must select every ordering expression");
        }
        indexes[index] = selection.expressions().size() + hidden.size() + 1;
        hidden.add(new HiddenSelection(expression, Identifier.of("__skis_order_" + index)));
      }
    }
    List<PropertyRuntime<?, ?>> resolvedOrderProperties = new ArrayList<>(orderBy.size());
    for (SortSpecification<E> item : orderBy) {
      resolvedOrderProperties.add(runtimeScope.property(item.column()));
    }
    List<PropertyRuntime<?, ?>> orderProperties = List.copyOf(resolvedOrderProperties);
    RowDecoder<OrderedRow<R>> decoder =
        (resultSet, context) -> {
          var value = selection.decoder().decode(resultSet, context);
          List<@Nullable Object> orderValues = new ArrayList<>(orderBy.size());
          for (int index = 0; index < orderBy.size(); index++) {
            orderValues.add(read(orderProperties.get(index), resultSet, indexes[index], context));
          }
          return new OrderedRow<>(value, orderValues);
        };
    return compileResolvedSelection(
        model,
        structure,
        runtimeScope,
        new Selection<>(selection.expressions(), decoder),
        orderBy,
        distinct,
        pagination,
        hidden);
  }

  <E, R> QueryCompilation<R> compileSelection(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      SelectedResult<?, R> selected,
      List<QueryJoin> joins,
      @Nullable QueryCondition condition,
      List<SortSpecification<E>> orderBy,
      boolean distinct,
      QueryPagination pagination,
      List<HiddenSelection> hidden) {
    requireCanonicalModel(model, table);
    CompiledQueryStructure structure = QueryStructureCompiler.compile(table, joins, condition);
    TableRuntimeScope runtimeScope =
        TableRuntimeScope.resolve(runtimeRegistry, structure.fromClause());
    Selection<R> selection = selected.resolve(runtimeScope);
    return compileResolvedSelection(
        model, structure, runtimeScope, selection, orderBy, distinct, pagination, hidden);
  }

  private <E, R> QueryCompilation<R> compileResolvedSelection(
      EntityRuntimeModel<E> model,
      CompiledQueryStructure structure,
      TableRuntimeScope runtimeScope,
      Selection<R> selection,
      List<SortSpecification<E>> orderBy,
      boolean distinct,
      QueryPagination pagination,
      List<HiddenSelection> hidden) {
    List<OrderByItem> orderAst = orderBy.stream().map(SortSpecification::ast).toList();
    validatedStatement(
        () ->
            new SelectStatement(
                distinct,
                selection.expressions(),
                hidden,
                structure.fromClause(),
                structure.where(),
                orderAst,
                null));
    InputsBuilder<E> inputs = new InputsBuilder<>(runtimeScope, structure);
    SelectPagination paginationAst = inputs.pagination(orderBy, pagination);
    SelectStatement statement =
        validatedStatement(
            () ->
                new SelectStatement(
                    distinct,
                    selection.expressions(),
                    hidden,
                    structure.fromClause(),
                    structure.where(),
                    orderAst,
                    paginationAst));
    CompiledQueryPlan<R, Object> plan =
        compilePlan(model, statement, inputs.logicalParameters(), selection.decoder());
    return new QueryCompilation<>(plan, inputs.argument(), statement);
  }

  private <E, R> CompiledQueryPlan<R, Object> compilePlanFromProperties(
      EntityRuntimeModel<E> model,
      StatementAst statement,
      List<PropertyMeta<E, ?>> properties,
      RowDecoder<R> rowDecoder) {
    List<LogicalParameter<E>> parameters = new ArrayList<>(properties.size());
    for (int ordinal = 0; ordinal < properties.size(); ordinal++) {
      PropertyMeta<E, ?> property = properties.get(ordinal);
      parameters.add(
          LogicalParameter.property(expectedSlot(ordinal, property), 0, model.property(property)));
    }
    return compilePlan(model, statement, parameters, rowDecoder);
  }

  private <E, R> CompiledQueryPlan<R, Object> compilePlan(
      EntityRuntimeModel<E> model,
      StatementAst statement,
      List<LogicalParameter<E>> logicalParameters,
      RowDecoder<R> rowDecoder) {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(rowDecoder, "rowDecoder");
    validateLogicalParameters(logicalParameters);
    dialect.validate(statement);
    RenderedSql rendered = dialect.renderer().render(statement);
    List<RenderedBinding<E>> renderedBindings =
        renderedBindings(model, logicalParameters, rendered);
    int logicalParameterCount = logicalParameters.size();
    return new CompiledQueryPlan<>(
        dialect.id(),
        rendered,
        (preparedStatement, firstIndex, argument, context) -> {
          List<?> values = requireArguments(argument, logicalParameterCount);
          int index = firstIndex;
          for (RenderedBinding<E> binding : renderedBindings) {
            binding
                .parameter()
                .bind(preparedStatement, index, values.get(binding.argumentOrdinal()), context);
            index++;
          }
          return index;
        },
        rowDecoder);
  }

  private <E> List<RenderedBinding<E>> renderedBindings(
      EntityRuntimeModel<E> model,
      List<LogicalParameter<E>> logicalParameters,
      RenderedSql rendered) {
    boolean[] seen = new boolean[logicalParameters.size()];
    List<RenderedBinding<E>> bindings = new ArrayList<>(rendered.parameterCount());
    for (ParameterSlot<?> renderedSlot : rendered.parameters()) {
      int ordinal = renderedSlot.ordinal();
      if (ordinal < 0 || ordinal >= logicalParameters.size()) {
        throw unexpectedParameterShape(model);
      }
      LogicalParameter<E> logical = logicalParameters.get(ordinal);
      if (!logical.matches(renderedSlot)) {
        throw unexpectedParameterShape(model);
      }
      seen[ordinal] = true;
      bindings.add(new RenderedBinding<>(logical, ordinal));
    }
    for (boolean present : seen) {
      if (!present) {
        throw unexpectedParameterShape(model);
      }
    }
    return List.copyOf(bindings);
  }

  private QueryValidationException unexpectedParameterShape(EntityRuntimeModel<?> model) {
    return new QueryValidationException(
        "dialect '"
            + dialect.id()
            + "' rendered an unexpected parameter shape for entity '"
            + model.entity().entityName()
            + "'");
  }

  private static void validateLogicalParameters(List<? extends LogicalParameter<?>> parameters) {
    for (int ordinal = 0; ordinal < parameters.size(); ordinal++) {
      LogicalParameter<?> parameter = Objects.requireNonNull(parameters.get(ordinal), "parameter");
      if (parameter.descriptor().ordinal() != ordinal) {
        throw new QueryValidationException(
            "logical parameter ordinals must be dense from zero; expected "
                + ordinal
                + " but found "
                + parameter.descriptor().ordinal());
      }
    }
  }

  private static <S extends StatementAst> S validatedStatement(Supplier<S> factory) {
    try {
      return factory.get();
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(failure.getMessage(), failure);
    }
  }

  private static <E, V> ParameterSlot<V> expectedSlot(int ordinal, PropertyMeta<E, V> property) {
    return new ParameterSlot<>(
        ordinal,
        property.javaType(),
        SqlType.fromJavaType(property.javaType()),
        Nullability.NON_NULL);
  }

  private static List<?> requireArguments(Object argument, int expectedCount) throws SQLException {
    Objects.requireNonNull(argument, "argument");
    if (expectedCount == 0) {
      if (argument != NoParameters.INSTANCE) {
        throw new SQLException("compiled query does not accept parameters");
      }
      return List.of();
    }
    if (argument instanceof QueryArguments(List<Object> values)) {
      if (values.size() != expectedCount) {
        throw new SQLException(
            "compiled query requires "
                + expectedCount
                + " logical parameters but received "
                + values.size());
      }
      return values;
    }
    if (expectedCount == 1 && argument != NoParameters.INSTANCE) {
      return List.of(argument);
    }
    throw new SQLException("compiled query requires " + expectedCount + " logical parameters");
  }

  private static Object argument(List<Object> arguments) {
    return arguments.isEmpty() ? NoParameters.INSTANCE : new QueryArguments(arguments);
  }

  private static <E> PredicateShape<E> predicateShape(@Nullable QueryPredicate<E> predicate) {
    if (predicate == null) {
      return new PredicateShape<>(null, List.of());
    }
    CompiledQueryPredicate<E> compiled = predicate.compile();
    return new PredicateShape<>(compiled.ast(), compiled.properties());
  }

  private static <E> PredicateShape<E> equalityShape(
      QueryTable<E> table, @Nullable PropertyMeta<E, ?> property) {
    if (property == null) {
      return new PredicateShape<>(null, List.of());
    }
    return equalityShapeTyped(table, property);
  }

  private static <E, V> PredicateShape<E> equalityShapeTyped(
      QueryTable<E> table, PropertyMeta<E, V> property) {
    ColumnExpression<E, V> column = table.expression(property);
    ParameterSlot<V> slot =
        new ParameterSlot<>(0, property.javaType(), column.sqlType(), Nullability.NON_NULL);
    return new PredicateShape<>(column.eq(slot), List.of(property));
  }

  private static <E> void requireCanonicalModel(EntityRuntimeModel<E> model, QueryTable<E> table) {
    if (table.entity() != model.entity()) {
      throw new QueryValidationException(
          "query table does not use the canonical runtime metadata of entity '"
              + model.entity().entityName()
              + "'");
    }
  }

  private static boolean isCompleteEntitySelection(
      EntityRuntimeModel<?> model, List<SqlExpression<?>> expressions) {
    if (model.entity().primaryKey().isEmpty()
        || expressions.size() != model.entity().properties().size()) {
      return false;
    }
    for (int index = 0; index < expressions.size(); index++) {
      if (!(expressions.get(index) instanceof ColumnExpression<?, ?> column)
          || column.property() != model.entity().properties().get(index)) {
        return false;
      }
    }
    return true;
  }

  private static <E, V> @Nullable V read(
      PropertyRuntime<E, V> runtime,
      java.sql.ResultSet resultSet,
      int index,
      RowReadContext context)
      throws SQLException {
    return runtime.codec().read(resultSet, index, context);
  }

  record Selection<R>(List<SqlExpression<?>> expressions, RowDecoder<R> decoder) {

    static <R> Selection<R> of(
        List<? extends SqlExpression<?>> expressions, RowDecoder<R> decoder) {
      List<SqlExpression<?>> copy = List.copyOf(expressions);
      return new Selection<>(copy, decoder);
    }

    Selection {
      expressions = List.copyOf(expressions);
      if (expressions.isEmpty()) {
        throw new QueryValidationException("a selection must contain at least one expression");
      }
      Objects.requireNonNull(decoder, "decoder");
    }
  }

  private record PredicateShape<E>(
      @Nullable SqlPredicate ast, List<PropertyMeta<E, ?>> properties) {

    private PredicateShape {
      properties = List.copyOf(properties);
    }
  }

  private record RenderedBinding<E>(LogicalParameter<E> parameter, int argumentOrdinal) {}

  private enum ScalarBinding {
    NONE,
    INTEGER,
    LONG
  }

  private record LogicalParameter<E>(
      ParameterSlot<?> descriptor,
      int occurrenceOrdinal,
      @Nullable PropertyRuntime<?, ?> runtime,
      ScalarBinding scalarBinding) {

    private LogicalParameter {
      Objects.requireNonNull(descriptor, "descriptor");
      Objects.requireNonNull(scalarBinding, "scalarBinding");
      if (runtime != null && occurrenceOrdinal < 0) {
        throw new IllegalArgumentException(
            "property parameter occurrence ordinal must not be negative");
      }
      if (runtime == null && occurrenceOrdinal != -1) {
        throw new IllegalArgumentException(
            "scalar parameter must not declare a table occurrence ordinal");
      }
    }

    static <E> LogicalParameter<E> property(
        ParameterSlot<?> descriptor, int occurrenceOrdinal, PropertyRuntime<?, ?> runtime) {
      return new LogicalParameter<>(
          descriptor,
          occurrenceOrdinal,
          Objects.requireNonNull(runtime, "runtime"),
          ScalarBinding.NONE);
    }

    static <E> LogicalParameter<E> integer(ParameterSlot<Integer> descriptor) {
      return new LogicalParameter<>(descriptor, -1, null, ScalarBinding.INTEGER);
    }

    static <E> LogicalParameter<E> longValue(ParameterSlot<Long> descriptor) {
      return new LogicalParameter<>(descriptor, -1, null, ScalarBinding.LONG);
    }

    boolean matches(ParameterSlot<?> slot) {
      return descriptor.ordinal() == slot.ordinal()
          && descriptor.javaType().equals(slot.javaType())
          && descriptor.sqlType() == slot.sqlType()
          && descriptor.nullability() == slot.nullability();
    }

    void bind(PreparedStatement statement, int index, Object value, JdbcWriteContext context)
        throws SQLException {
      if (runtime != null) {
        runtime.bind(statement, index, value, context);
        return;
      }
      switch (scalarBinding) {
        case INTEGER -> statement.setInt(index, requireType(value, Integer.class));
        case LONG -> statement.setLong(index, requireType(value, Long.class));
        case NONE -> throw new SQLException("logical parameter has no binder");
      }
    }

    private static <T> T requireType(Object value, Class<T> type) throws SQLException {
      if (!type.isInstance(value)) {
        throw new SQLException(
            "pagination parameter requires "
                + type.getTypeName()
                + " but received "
                + value.getClass().getTypeName());
      }
      return type.cast(value);
    }
  }

  private static final class InputsBuilder<E> {

    private final TableRuntimeScope runtimeScope;
    private final List<LogicalParameter<E>> logicalParameters = new ArrayList<>();
    private final List<Object> arguments = new ArrayList<>();

    private InputsBuilder(TableRuntimeScope runtimeScope, CompiledQueryStructure structure) {
      this.runtimeScope = Objects.requireNonNull(runtimeScope, "runtimeScope");
      for (int index = 0; index < structure.parameterColumns().size(); index++) {
        QueryColumn<?, ?> column = structure.parameterColumns().get(index);
        addConditionProperty(column, structure.arguments().get(index));
      }
    }

    private @Nullable SelectPagination pagination(
        List<SortSpecification<E>> orderBy, QueryPagination pagination) {
      return switch (pagination) {
        case QueryPagination.None ignored -> null;
        case QueryPagination.LimitOnly limit -> new Limit(addInteger(limit.limit()));
        case QueryPagination.Offset offset ->
            new OffsetLimit(addInteger(offset.limit()), addLong(offset.offset()));
        case QueryPagination.Keyset keyset ->
            new KeysetSeek(keysetPredicate(orderBy, keyset.values()), addInteger(keyset.limit()));
      };
    }

    private SqlPredicate keysetPredicate(
        List<SortSpecification<E>> orderBy, List<@Nullable Object> values) {
      if (orderBy.size() != values.size() || orderBy.isEmpty()) {
        throw new QueryValidationException(
            "keyset continuation value count must match a non-empty ORDER BY");
      }
      @SuppressWarnings("unchecked")
      ParameterSlot<Object>[] slots = new ParameterSlot[values.size()];
      boolean[] nullable = new boolean[values.size()];
      for (int index = 0; index < values.size(); index++) {
        SortSpecification<E> sort = orderBy.get(index);
        Object value = values.get(index);
        nullable[index] = runtimeScope.effectiveNullability(sort.column()).isNullable();
        if (nullable[index] && sort.nullPlacement() == NullPlacement.DIALECT_DEFAULT) {
          throw new QueryValidationException(
              "effectively nullable keyset ordering property '"
                  + sort.column().property().name()
                  + "' must declare nullsFirst() or nullsLast()");
        }
        if (value == null) {
          if (!nullable[index]) {
            throw new QueryValidationException(
                "keyset continuation contains null for non-null property '"
                    + sort.column().property().name()
                    + "'");
          }
          continue;
        }
        if (!sort.column().javaType().isInstance(value)) {
          throw new QueryValidationException(
              "keyset continuation Java type does not match property '"
                  + sort.column().property().name()
                  + "'");
        }
        slots[index] = addPropertyUntyped(sort.column(), value);
      }

      List<SqlPredicate> disjunctions = new ArrayList<>();
      for (int index = 0; index < orderBy.size(); index++) {
        SqlPredicate after =
            after(orderBy.get(index), values.get(index), slots[index], nullable[index]);
        if (after == null) {
          continue;
        }
        List<SqlPredicate> conjunctions = new ArrayList<>(index + 1);
        for (int prefix = 0; prefix < index; prefix++) {
          conjunctions.add(equal(orderBy.get(prefix), values.get(prefix), slots[prefix]));
        }
        conjunctions.add(after);
        disjunctions.add(combine(LogicalOperator.AND, conjunctions));
      }
      if (disjunctions.isEmpty()) {
        return falsePredicate(orderBy.getFirst().column());
      }
      return combine(LogicalOperator.OR, disjunctions);
    }

    private ParameterSlot<Integer> addInteger(int value) {
      int ordinal = arguments.size();
      ParameterSlot<Integer> slot =
          new ParameterSlot<>(ordinal, Integer.class, SqlType.INTEGER, Nullability.NON_NULL);
      logicalParameters.add(LogicalParameter.integer(slot));
      arguments.add(value);
      return slot;
    }

    private ParameterSlot<Long> addLong(long value) {
      int ordinal = arguments.size();
      ParameterSlot<Long> slot =
          new ParameterSlot<>(ordinal, Long.class, SqlType.BIGINT, Nullability.NON_NULL);
      logicalParameters.add(LogicalParameter.longValue(slot));
      arguments.add(value);
      return slot;
    }

    private <V> ParameterSlot<V> addProperty(
        QueryColumn<?, V> column,
        Object value,
        int occurrenceOrdinal,
        PropertyRuntime<?, V> runtime) {
      int ordinal = arguments.size();
      ParameterSlot<V> slot =
          new ParameterSlot<>(ordinal, column.javaType(), column.sqlType(), Nullability.NON_NULL);
      logicalParameters.add(LogicalParameter.property(slot, occurrenceOrdinal, runtime));
      arguments.add(value);
      return slot;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ParameterSlot<Object> addPropertyUntyped(QueryColumn<?, ?> column, Object value) {
      TableRuntimeScope.Occurrence<?> occurrence = runtimeScope.require(column.table());
      return (ParameterSlot)
          addProperty(
              column,
              value,
              occurrence.occurrenceOrdinal(),
              (PropertyRuntime) runtimeScope.property((QueryColumn) column));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addConditionProperty(QueryColumn<?, ?> column, Object value) {
      TableRuntimeScope.Occurrence<?> occurrence = runtimeScope.require(column.table());
      PropertyRuntime<?, ?> runtime = runtimeScope.property((QueryColumn) column);
      addProperty(column, value, occurrence.occurrenceOrdinal(), (PropertyRuntime) runtime);
    }

    private List<LogicalParameter<E>> logicalParameters() {
      return List.copyOf(logicalParameters);
    }

    private Object argument() {
      return QueryPlanCompiler.argument(arguments);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SqlPredicate equal(
        SortSpecification<?> sort, @Nullable Object value, @Nullable ParameterSlot<Object> slot) {
      ColumnExpression column = sort.column().expression();
      return value == null
          ? column.isNull()
          : new ComparisonPredicate(
              column, ComparisonOperator.EQUAL, Objects.requireNonNull(slot, "slot"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static @Nullable SqlPredicate after(
        SortSpecification<?> sort,
        @Nullable Object value,
        @Nullable ParameterSlot<Object> slot,
        boolean nullable) {
      ColumnExpression column = sort.column().expression();
      if (value == null) {
        return sort.nullPlacement() == NullPlacement.FIRST ? column.isNotNull() : null;
      }
      ComparisonOperator operator =
          sort.direction() == SortDirection.ASC
              ? ComparisonOperator.GREATER_THAN
              : ComparisonOperator.LESS_THAN;
      SqlPredicate comparison =
          new ComparisonPredicate(column, operator, Objects.requireNonNull(slot, "slot"));
      if (nullable && sort.nullPlacement() == NullPlacement.LAST) {
        return new LogicalPredicate(LogicalOperator.OR, List.of(comparison, column.isNull()));
      }
      return comparison;
    }

    private static SqlPredicate combine(LogicalOperator operator, List<SqlPredicate> predicates) {
      return predicates.size() == 1
          ? predicates.getFirst()
          : new LogicalPredicate(operator, predicates);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SqlPredicate falsePredicate(QueryColumn<?, ?> column) {
      return new InPredicate(column.expression(), List.of(), false);
    }
  }
}
