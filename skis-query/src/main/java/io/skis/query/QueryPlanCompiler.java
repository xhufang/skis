package io.skis.query;

import io.skis.dialect.Dialect;
import io.skis.dialect.RenderedSql;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.mapping.EntityRuntimeModel;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Compiles immutable single-table query shapes into value-independent JDBC plans. */
final class QueryPlanCompiler {

  private final Dialect dialect;

  QueryPlanCompiler(Dialect dialect) {
    this.dialect = Objects.requireNonNull(dialect, "dialect");
  }

  /** Compiles the no-predicate or one-property equality Fast Path. */
  <E> CompiledQueryPlan<E, Object> compile(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      @Nullable PropertyMeta<E, ?> equalityProperty) {
    requireCanonicalModel(model, table);
    PredicateShape<E> shape = equalityShape(table, equalityProperty);
    SelectStatement statement = new SelectStatement(table.selections(), table, shape.ast());
    return compilePlanFromProperties(model, statement, shape.properties(), model.fullRowDecoder());
  }

  <E> CompiledQueryPlan<E, Object> compileQuery(
      EntityRuntimeModel<E> model, QueryTable<E> table, @Nullable QueryPredicate<E> predicate) {
    requireCanonicalModel(model, table);
    PredicateShape<E> shape = predicateShape(predicate);
    SelectStatement statement = new SelectStatement(table.selections(), table, shape.ast());
    return compilePlanFromProperties(model, statement, shape.properties(), model.fullRowDecoder());
  }

  <E, R> CompiledQueryPlan<R, Object> compileProjection(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      Projection<E, R> projection,
      @Nullable QueryPredicate<E> predicate) {
    requireCanonicalModel(model, table);
    Selection<E, R> selection = projectionSelection(model, table, projection);
    PredicateShape<E> shape = predicateShape(predicate);
    SelectStatement statement = new SelectStatement(selection.expressions(), table, shape.ast());
    return compilePlanFromProperties(model, statement, shape.properties(), selection.decoder());
  }

  <E> QueryCompilation<E> compileEntity(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      @Nullable QueryPredicate<E> predicate,
      List<SortSpecification<E>> orderBy,
      boolean distinct,
      QueryPagination pagination) {
    return compileSelection(
        model,
        table,
        Selection.of(table.selections(), model.fullRowDecoder()),
        predicate,
        orderBy,
        distinct,
        pagination,
        List.of());
  }

  <E, R> QueryCompilation<R> compileProjection(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      Projection<E, R> projection,
      @Nullable QueryPredicate<E> predicate,
      List<SortSpecification<E>> orderBy,
      boolean distinct,
      QueryPagination pagination) {
    return compileSelection(
        model,
        table,
        projectionSelection(model, table, projection),
        predicate,
        orderBy,
        distinct,
        pagination,
        List.of());
  }

  <E> QueryCompilation<OrderedRow<E>> compileOrderedEntity(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      @Nullable QueryPredicate<E> predicate,
      List<SortSpecification<E>> orderBy,
      boolean distinct,
      QueryPagination pagination) {
    return compileOrdered(
        model,
        table,
        Selection.of(table.selections(), model.fullRowDecoder()),
        predicate,
        orderBy,
        distinct,
        pagination);
  }

  <E, R> QueryCompilation<OrderedRow<R>> compileOrderedProjection(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      Projection<E, R> projection,
      @Nullable QueryPredicate<E> predicate,
      List<SortSpecification<E>> orderBy,
      boolean distinct,
      QueryPagination pagination) {
    return compileOrdered(
        model,
        table,
        projectionSelection(model, table, projection),
        predicate,
        orderBy,
        distinct,
        pagination);
  }

  <E, R> QueryCompilation<Long> compileCount(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      Selection<E, R> selection,
      @Nullable QueryPredicate<E> predicate,
      boolean distinct) {
    requireCanonicalModel(model, table);
    PredicateShape<E> shape = predicateShape(predicate);
    CountAst count =
        new CountAst(table, shape.ast(), countDistinctExpression(model, selection, distinct));
    CompiledQueryPlan<Long, Object> plan =
        compilePlanFromProperties(
            model,
            count,
            shape.properties(),
            (resultSet, context) -> {
              long value = resultSet.getLong(1);
              if (resultSet.wasNull()) {
                throw new SQLException("COUNT result was unexpectedly null");
              }
              return value;
            });
    return new QueryCompilation<>(plan, argument(shape.arguments()), count);
  }

  private static <E, R> @Nullable SqlExpression<?> countDistinctExpression(
      EntityRuntimeModel<E> model, Selection<E, R> selection, boolean distinct) {
    if (!distinct || isCompleteEntitySelection(model, selection.expressions())) {
      return null;
    }
    if (selection.expressions().size() != 1) {
      throw new QueryValidationException(
          "automatic count cannot preserve a multi-expression distinct result; provide an explicit count query");
    }
    return selection.expressions().getFirst();
  }

  <E> Selection<E, E> entitySelection(EntityRuntimeModel<E> model, QueryTable<E> table) {
    requireCanonicalModel(model, table);
    return Selection.of(table.selections(), model.fullRowDecoder());
  }

  <E, R> Selection<E, R> projectionSelection(
      EntityRuntimeModel<E> model, QueryTable<E> table, Projection<E, R> projection) {
    Objects.requireNonNull(projection, "projection").validateFrom(table);
    List<QueryColumn<E, ?>> columns = projection.columns(table);
    List<SqlExpression<?>> selections = new ArrayList<>(columns.size());
    for (QueryColumn<E, ?> column : columns) {
      selections.add(column.expression());
    }
    return new Selection<>(selections, projection.rowDecoder(model));
  }

  private <E, R> QueryCompilation<OrderedRow<R>> compileOrdered(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      Selection<E, R> selection,
      @Nullable QueryPredicate<E> predicate,
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
    RowDecoder<OrderedRow<R>> decoder =
        (resultSet, context) -> {
          var value = selection.decoder().decode(resultSet, context);
          List<@Nullable Object> orderValues = new ArrayList<>(orderBy.size());
          for (int index = 0; index < orderBy.size(); index++) {
            PropertyMeta<E, ?> property = orderBy.get(index).column().property();
            orderValues.add(read(model.property(property), resultSet, indexes[index], context));
          }
          return new OrderedRow<>(value, orderValues);
        };
    return compileSelection(
        model,
        table,
        new Selection<>(selection.expressions(), decoder),
        predicate,
        orderBy,
        distinct,
        pagination,
        hidden);
  }

  private <E, R> QueryCompilation<R> compileSelection(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      Selection<E, R> selection,
      @Nullable QueryPredicate<E> predicate,
      List<SortSpecification<E>> orderBy,
      boolean distinct,
      QueryPagination pagination,
      List<HiddenSelection> hidden) {
    requireCanonicalModel(model, table);
    PredicateShape<E> predicateShape = predicateShape(predicate);
    InputsBuilder<E> inputs = new InputsBuilder<>(model, predicateShape);
    SelectPagination paginationAst = inputs.pagination(orderBy, pagination);
    List<OrderByItem> orderAst = orderBy.stream().map(SortSpecification::ast).toList();
    SelectStatement statement =
        new SelectStatement(
            distinct,
            selection.expressions(),
            hidden,
            table,
            predicateShape.ast(),
            orderAst,
            paginationAst);
    CompiledQueryPlan<R, Object> plan =
        compilePlan(model, statement, inputs.logicalParameters(), selection.decoder());
    return new QueryCompilation<>(plan, inputs.argument(), statement);
  }

  private <E, R> CompiledQueryPlan<R, Object> compilePlanFromProperties(
      EntityRuntimeModel<E> model,
      io.skis.sql.ast.StatementAst statement,
      List<PropertyMeta<E, ?>> properties,
      RowDecoder<R> rowDecoder) {
    List<LogicalParameter<E>> parameters = new ArrayList<>(properties.size());
    for (int ordinal = 0; ordinal < properties.size(); ordinal++) {
      PropertyMeta<E, ?> property = properties.get(ordinal);
      parameters.add(
          LogicalParameter.property(expectedSlot(ordinal, property), model.property(property)));
    }
    return compilePlan(model, statement, parameters, rowDecoder);
  }

  private <E, R> CompiledQueryPlan<R, Object> compilePlan(
      EntityRuntimeModel<E> model,
      io.skis.sql.ast.StatementAst statement,
      List<LogicalParameter<E>> logicalParameters,
      RowDecoder<R> rowDecoder) {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(rowDecoder, "rowDecoder");
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
      return new PredicateShape<>(null, List.of(), List.of());
    }
    CompiledQueryPredicate<E> compiled = predicate.compile();
    return new PredicateShape<>(compiled.ast(), compiled.properties(), compiled.arguments());
  }

  private static <E> PredicateShape<E> equalityShape(
      QueryTable<E> table, @Nullable PropertyMeta<E, ?> property) {
    if (property == null) {
      return new PredicateShape<>(null, List.of(), List.of());
    }
    return equalityShapeTyped(table, property);
  }

  private static <E, V> PredicateShape<E> equalityShapeTyped(
      QueryTable<E> table, PropertyMeta<E, V> property) {
    ColumnExpression<E, V> column = table.expression(property);
    ParameterSlot<V> slot =
        new ParameterSlot<>(0, property.javaType(), column.sqlType(), Nullability.NON_NULL);
    return new PredicateShape<>(column.eq(slot), List.of(property), List.of());
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

  record Selection<E, R>(List<SqlExpression<?>> expressions, RowDecoder<R> decoder) {

    static <E, R> Selection<E, R> of(
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
      @Nullable SqlPredicate ast, List<PropertyMeta<E, ?>> properties, List<Object> arguments) {

    private PredicateShape {
      properties = List.copyOf(properties);
      arguments = List.copyOf(arguments);
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
      @Nullable PropertyRuntime<E, ?> runtime,
      ScalarBinding scalarBinding) {

    private LogicalParameter {
      Objects.requireNonNull(descriptor, "descriptor");
      Objects.requireNonNull(scalarBinding, "scalarBinding");
    }

    static <E> LogicalParameter<E> property(
        ParameterSlot<?> descriptor, PropertyRuntime<E, ?> runtime) {
      return new LogicalParameter<>(
          descriptor, Objects.requireNonNull(runtime, "runtime"), ScalarBinding.NONE);
    }

    static <E> LogicalParameter<E> integer(ParameterSlot<Integer> descriptor) {
      return new LogicalParameter<>(descriptor, null, ScalarBinding.INTEGER);
    }

    static <E> LogicalParameter<E> longValue(ParameterSlot<Long> descriptor) {
      return new LogicalParameter<>(descriptor, null, ScalarBinding.LONG);
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

    private final EntityRuntimeModel<E> model;
    private final List<LogicalParameter<E>> logicalParameters = new ArrayList<>();
    private final List<Object> arguments = new ArrayList<>();

    private InputsBuilder(EntityRuntimeModel<E> model, PredicateShape<E> predicate) {
      this.model = Objects.requireNonNull(model, "model");
      for (int index = 0; index < predicate.properties().size(); index++) {
        PropertyMeta<E, ?> property = predicate.properties().get(index);
        addProperty(
            property, predicate.arguments().get(index), SqlType.fromJavaType(property.javaType()));
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
      for (int index = 0; index < values.size(); index++) {
        SortSpecification<E> sort = orderBy.get(index);
        Object value = values.get(index);
        if (sort.column().nullable() && sort.nullPlacement() == NullPlacement.DIALECT_DEFAULT) {
          throw new QueryValidationException(
              "nullable keyset ordering property '"
                  + sort.column().property().name()
                  + "' must declare nullsFirst() or nullsLast()");
        }
        if (value == null) {
          if (!sort.column().nullable()) {
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
        SqlPredicate after = after(orderBy.get(index), values.get(index), slots[index]);
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
        PropertyMeta<E, V> property, Object value, SqlType sqlType) {
      int ordinal = arguments.size();
      ParameterSlot<V> slot =
          new ParameterSlot<>(ordinal, property.javaType(), sqlType, Nullability.NON_NULL);
      logicalParameters.add(LogicalParameter.property(slot, model.property(property)));
      arguments.add(value);
      return slot;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ParameterSlot<Object> addPropertyUntyped(QueryColumn<E, ?> column, Object value) {
      return (ParameterSlot) addProperty((PropertyMeta) column.property(), value, column.sqlType());
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
        SortSpecification<?> sort, @Nullable Object value, @Nullable ParameterSlot<Object> slot) {
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
      if (column.nullable() && sort.nullPlacement() == NullPlacement.LAST) {
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
