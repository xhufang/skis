package io.skis.query;

import io.skis.dialect.Dialect;
import io.skis.dialect.RenderedSql;
import io.skis.jdbc.CompiledQueryPlan;
import io.skis.mapping.EntityRuntimeModel;
import io.skis.mapping.EntityRuntimeRegistry;
import io.skis.mapping.JdbcTypeCodec;
import io.skis.mapping.JdbcWriteContext;
import io.skis.mapping.RowDecoder;
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
import io.skis.sql.ast.NullOperator;
import io.skis.sql.ast.NullPredicate;
import io.skis.sql.ast.Nullability;
import io.skis.sql.ast.OffsetLimit;
import io.skis.sql.ast.OrderByItem;
import io.skis.sql.ast.ParameterSlot;
import io.skis.sql.ast.SelectPagination;
import io.skis.sql.ast.SelectStatement;
import io.skis.sql.ast.SemanticValidator;
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
        constructedStatement(() -> new SelectStatement(table.selections(), table, shape.ast()));
    return compilePlanFromProperties(model, statement, shape.properties(), model.fullRowDecoder());
  }

  <E> CompiledQueryPlan<E, Object> compileQuery(
      EntityRuntimeModel<E> model, QueryTable<E> table, CompiledQueryStructure structure) {
    requireCanonicalModel(model, table);
    Objects.requireNonNull(structure, "structure");
    TableRuntimeScope runtimeScope =
        TableRuntimeScope.resolve(runtimeRegistry, structure.fromClause());
    SelectStatement statement =
        constructedStatement(
            () ->
                new SelectStatement(table.selections(), structure.fromClause(), structure.where()));
    InputsBuilder<E> inputs = new InputsBuilder<>(runtimeScope, structure);
    return compilePlan(model, statement, inputs.logicalParameters(), model.fullRowDecoder());
  }

  <E, R> QueryCompilation<Long> compileCount(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      SelectedResult<R> selected,
      List<QueryJoin> joins,
      @Nullable QueryCondition condition,
      boolean distinct) {
    return compileCount(
        model, table, selected, QueryStructureCompiler.compile(table, joins, condition), distinct);
  }

  <E, R> QueryCompilation<Long> compileCount(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      SelectedResult<R> selected,
      CompiledQueryStructure structure,
      boolean distinct) {
    requireCanonicalModel(model, table);
    Objects.requireNonNull(structure, "structure");
    TableRuntimeScope runtimeScope =
        TableRuntimeScope.resolve(runtimeRegistry, structure.fromClause());
    ResolvedResultShape<R> selection = selected.resolve(runtimeScope);
    return compileResolvedCount(
        model,
        structure,
        runtimeScope,
        selection,
        distinct
            ? selected.automaticDistinctCountExpression(!structure.fromClause().joins().isEmpty())
            : null);
  }

  private <E, R> QueryCompilation<Long> compileResolvedCount(
      EntityRuntimeModel<E> model,
      CompiledQueryStructure structure,
      TableRuntimeScope runtimeScope,
      ResolvedResultShape<R> selection,
      @Nullable SqlExpression<?> distinctExpression) {
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
        constructedStatement(
            () -> new CountAst(structure.fromClause(), structure.where(), distinctExpression));
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

  <E, R> QueryCompilation<OrderedRow<R>> compileOrdered(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      SelectedResult<R> selected,
      List<QueryJoin> joins,
      @Nullable QueryCondition condition,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPagination pagination) {
    return compileOrdered(
        model,
        table,
        selected,
        QueryStructureCompiler.compile(table, joins, condition),
        orderBy,
        distinct,
        pagination);
  }

  <E, R> QueryCompilation<OrderedRow<R>> compileOrdered(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      SelectedResult<R> selected,
      CompiledQueryStructure structure,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPagination pagination) {
    requireCanonicalModel(model, table);
    Objects.requireNonNull(structure, "structure");
    TableRuntimeScope runtimeScope =
        TableRuntimeScope.resolve(runtimeRegistry, structure.fromClause());
    ResolvedResultShape<R> selection = selected.resolve(runtimeScope);
    return compileResolvedOrdered(
        model, structure, runtimeScope, selection, orderBy, distinct, pagination);
  }

  private <E, R> QueryCompilation<OrderedRow<R>> compileResolvedOrdered(
      EntityRuntimeModel<E> model,
      CompiledQueryStructure structure,
      TableRuntimeScope runtimeScope,
      ResolvedResultShape<R> selection,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPagination pagination) {
    List<HiddenSelection> hidden = new ArrayList<>();
    int[] indexes = new int[orderBy.size()];
    for (int index = 0; index < orderBy.size(); index++) {
      SqlExpression<?> expression = orderBy.get(index).expression();
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
    List<ResolvedValueMapping<?>> resolvedOrderMappings = new ArrayList<>(orderBy.size());
    for (SortSpecification item : orderBy) {
      resolvedOrderMappings.add(ResolvedValueMapping.resolve(item.selectable(), runtimeScope));
    }
    List<ResolvedValueMapping<?>> orderMappings = List.copyOf(resolvedOrderMappings);
    RowDecoder<OrderedRow<R>> decoder =
        (resultSet, context) -> {
          var value = selection.decoder().decode(resultSet, context);
          List<@Nullable Object> orderValues = new ArrayList<>(orderBy.size());
          for (int index = 0; index < orderBy.size(); index++) {
            orderValues.add(orderMappings.get(index).read(resultSet, indexes[index], context));
          }
          return new OrderedRow<>(value, orderValues);
        };
    return compileResolvedSelection(
        model,
        structure,
        runtimeScope,
        new ResolvedResultShape<>(selection.expressions(), decoder, selection.selections()),
        orderBy,
        distinct,
        pagination,
        hidden);
  }

  <E, R> QueryCompilation<R> compileSelection(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      SelectedResult<R> selected,
      List<QueryJoin> joins,
      @Nullable QueryCondition condition,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPagination pagination,
      List<HiddenSelection> hidden) {
    return compileSelection(
        model,
        table,
        selected,
        QueryStructureCompiler.compile(table, joins, condition),
        orderBy,
        distinct,
        pagination,
        hidden);
  }

  <E, R> QueryCompilation<R> compileSelection(
      EntityRuntimeModel<E> model,
      QueryTable<E> table,
      SelectedResult<R> selected,
      CompiledQueryStructure structure,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPagination pagination,
      List<HiddenSelection> hidden) {
    requireCanonicalModel(model, table);
    Objects.requireNonNull(structure, "structure");
    TableRuntimeScope runtimeScope =
        TableRuntimeScope.resolve(runtimeRegistry, structure.fromClause());
    ResolvedResultShape<R> selection = selected.resolve(runtimeScope);
    return compileResolvedSelection(
        model, structure, runtimeScope, selection, orderBy, distinct, pagination, hidden);
  }

  private <E, R> QueryCompilation<R> compileResolvedSelection(
      EntityRuntimeModel<E> model,
      CompiledQueryStructure structure,
      TableRuntimeScope runtimeScope,
      ResolvedResultShape<R> selection,
      List<SortSpecification> orderBy,
      boolean distinct,
      QueryPagination pagination,
      List<HiddenSelection> hidden) {
    List<OrderByItem> orderAst = orderBy.stream().map(SortSpecification::ast).toList();
    InputsBuilder<E> inputs = new InputsBuilder<>(runtimeScope, structure);
    SelectPagination paginationAst = inputs.pagination(orderBy, pagination);
    SelectStatement statement =
        constructedStatement(
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
          LogicalParameter.codec(
              expectedSlot(ordinal, property), model.property(property).codec()));
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
    RenderedSql rendered;
    try {
      SemanticValidator.validateComplete(statement);
      dialect.validate(statement);
      rendered = dialect.renderer().render(statement);
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(failure.getMessage(), failure);
    }
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

  private static <S extends StatementAst> void validatedStatement(Supplier<S> factory) {
    try {
      S statement = factory.get();
      SemanticValidator.validateComplete(statement);
    } catch (IllegalArgumentException failure) {
      throw new QueryValidationException(failure.getMessage(), failure);
    }
  }

  private static <S extends StatementAst> S constructedStatement(Supplier<S> factory) {
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
    if (argument instanceof QueryArguments(List<@Nullable Object> values)) {
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

  private static Object argument(List<@Nullable Object> arguments) {
    return arguments.isEmpty() ? NoParameters.INSTANCE : new QueryArguments(arguments);
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
      ParameterSlot<?> descriptor, @Nullable JdbcTypeCodec<?> codec, ScalarBinding scalarBinding) {

    private LogicalParameter {
      Objects.requireNonNull(descriptor, "descriptor");
      Objects.requireNonNull(scalarBinding, "scalarBinding");
      if (codec != null && scalarBinding != ScalarBinding.NONE) {
        throw new IllegalArgumentException("a logical parameter cannot use two binder kinds");
      }
      if (codec == null && scalarBinding == ScalarBinding.NONE) {
        throw new IllegalArgumentException("a logical parameter requires one binder kind");
      }
    }

    static <E> LogicalParameter<E> codec(ParameterSlot<?> descriptor, JdbcTypeCodec<?> codec) {
      return new LogicalParameter<>(
          descriptor, Objects.requireNonNull(codec, "codec"), ScalarBinding.NONE);
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

    void bind(
        PreparedStatement statement, int index, @Nullable Object value, JdbcWriteContext context)
        throws SQLException {
      if (value == null && !descriptor.nullability().isNullable()) {
        throw new SQLException("non-null query parameter is null at JDBC parameter index " + index);
      }
      if (codec != null) {
        bindCodec(codec, descriptor.javaType(), statement, index, value, context);
        return;
      }
      switch (scalarBinding) {
        case INTEGER -> statement.setInt(index, requireType(value, Integer.class));
        case LONG -> statement.setLong(index, requireType(value, Long.class));
        case NONE -> throw new SQLException("logical parameter has no binder");
      }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void bindCodec(
        JdbcTypeCodec codec,
        Class<?> javaType,
        PreparedStatement statement,
        int index,
        @Nullable Object value,
        JdbcWriteContext context)
        throws SQLException {
      Objects.requireNonNull(statement, "statement");
      Objects.requireNonNull(context, "context");
      if (index < 1) {
        throw new IllegalArgumentException("JDBC parameter index must be positive");
      }
      if (value != null && !javaType.isInstance(value)) {
        throw new SQLException(
            "query parameter requires "
                + javaType.getTypeName()
                + " but received "
                + value.getClass().getTypeName());
      }
      codec.bind(statement, index, value, context);
    }

    private static <T> T requireType(@Nullable Object value, Class<T> type) throws SQLException {
      if (!type.isInstance(value)) {
        throw new SQLException(
            "pagination parameter requires "
                + type.getTypeName()
                + " but received "
                + (value == null ? "null" : value.getClass().getTypeName()));
      }
      return type.cast(value);
    }
  }

  private static final class InputsBuilder<E> {

    private final TableRuntimeScope runtimeScope;
    private final List<LogicalParameter<E>> logicalParameters = new ArrayList<>();
    private final List<@Nullable Object> arguments = new ArrayList<>();

    private InputsBuilder(TableRuntimeScope runtimeScope, CompiledQueryStructure structure) {
      this.runtimeScope = Objects.requireNonNull(runtimeScope, "runtimeScope");
      List<@Nullable Object> conditionArguments = structure.arguments();
      for (int index = 0; index < structure.parameterSources().size(); index++) {
        Selectable<?> source = structure.parameterSources().get(index);
        addConditionMapping(
            source, structure.parameterSlots().get(index), conditionArguments.get(index));
      }
    }

    private @Nullable SelectPagination pagination(
        List<SortSpecification> orderBy, QueryPagination pagination) {
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
        List<SortSpecification> orderBy, List<@Nullable Object> values) {
      if (orderBy.size() != values.size() || orderBy.isEmpty()) {
        throw new QueryValidationException(
            "keyset continuation value count must match a non-empty ORDER BY");
      }
      @SuppressWarnings("unchecked")
      ParameterSlot<Object>[] slots = new ParameterSlot[values.size()];
      boolean[] nullable = new boolean[values.size()];
      for (int index = 0; index < values.size(); index++) {
        SortSpecification sort = orderBy.get(index);
        Object value = values.get(index);
        ResolvedValueMapping<?> mapping =
            ResolvedValueMapping.resolve(sort.selectable(), runtimeScope);
        nullable[index] = mapping.effectiveNullability().isNullable();
        if (nullable[index] && sort.nullPlacement() == NullPlacement.DIALECT_DEFAULT) {
          throw new QueryValidationException(
              "effectively nullable keyset ordering expression '"
                  + SelectableSupport.summary(sort.selectable())
                  + "' must declare nullsFirst() or nullsLast()");
        }
        if (value == null) {
          if (!nullable[index]) {
            throw new QueryValidationException(
                "keyset continuation contains null for non-null expression '"
                    + SelectableSupport.summary(sort.selectable())
                    + "'");
          }
          continue;
        }
        if (!mapping.javaType().isInstance(value)) {
          throw new QueryValidationException(
              "keyset continuation Java type does not match expression '"
                  + SelectableSupport.summary(sort.selectable())
                  + "'");
        }
        slots[index] = addValueMappingUntyped(mapping, value);
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
        return falsePredicate(orderBy.getFirst().expression());
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

    private <V> ParameterSlot<V> addValueMapping(ResolvedValueMapping<V> mapping, Object value) {
      int ordinal = arguments.size();
      ParameterSlot<V> slot =
          new ParameterSlot<>(ordinal, mapping.javaType(), mapping.sqlType(), Nullability.NON_NULL);
      logicalParameters.add(LogicalParameter.codec(slot, mapping.codec()));
      arguments.add(value);
      return slot;
    }

    @SuppressWarnings("unchecked")
    private ParameterSlot<Object> addValueMappingUntyped(
        ResolvedValueMapping<?> mapping, Object value) {
      return (ParameterSlot<Object>) (ParameterSlot<?>) addValueMapping(mapping, value);
    }

    private void addConditionMapping(
        Selectable<?> source, ParameterSlot<?> slot, @Nullable Object value) {
      ResolvedValueMapping<?> mapping = ResolvedValueMapping.resolve(source, runtimeScope);
      if (!slot.javaType().equals(mapping.javaType()) || slot.sqlType() != mapping.sqlType()) {
        throw new QueryValidationException(
            "query parameter slot descriptor does not match expression '"
                + SelectableSupport.summary(source)
                + "'");
      }
      logicalParameters.add(LogicalParameter.codec(slot, mapping.codec()));
      arguments.add(value);
    }

    private List<LogicalParameter<E>> logicalParameters() {
      return List.copyOf(logicalParameters);
    }

    private Object argument() {
      return QueryPlanCompiler.argument(arguments);
    }

    private static SqlPredicate equal(
        SortSpecification sort, @Nullable Object value, @Nullable ParameterSlot<Object> slot) {
      SqlExpression<?> expression = sort.expression();
      return value == null
          ? new NullPredicate(expression, NullOperator.IS_NULL)
          : comparison(expression, ComparisonOperator.EQUAL, Objects.requireNonNull(slot, "slot"));
    }

    private static @Nullable SqlPredicate after(
        SortSpecification sort,
        @Nullable Object value,
        @Nullable ParameterSlot<Object> slot,
        boolean nullable) {
      SqlExpression<?> expression = sort.expression();
      if (value == null) {
        return sort.nullPlacement() == NullPlacement.FIRST
            ? new NullPredicate(expression, NullOperator.IS_NOT_NULL)
            : null;
      }
      ComparisonOperator operator =
          sort.direction() == SortDirection.ASC
              ? ComparisonOperator.GREATER_THAN
              : ComparisonOperator.LESS_THAN;
      SqlPredicate comparison =
          comparison(expression, operator, Objects.requireNonNull(slot, "slot"));
      if (nullable && sort.nullPlacement() == NullPlacement.LAST) {
        return new LogicalPredicate(
            LogicalOperator.OR,
            List.of(comparison, new NullPredicate(expression, NullOperator.IS_NULL)));
      }
      return comparison;
    }

    private static SqlPredicate combine(LogicalOperator operator, List<SqlPredicate> predicates) {
      return predicates.size() == 1
          ? predicates.getFirst()
          : new LogicalPredicate(operator, predicates);
    }

    private static SqlPredicate falsePredicate(SqlExpression<?> expression) {
      return emptyMembership(expression);
    }

    private static <V> SqlPredicate comparison(
        SqlExpression<V> expression, ComparisonOperator operator, ParameterSlot<?> slot) {
      if (!expression.javaType().equals(slot.javaType())
          || expression.sqlType() != slot.sqlType()) {
        throw new QueryValidationException(
            "keyset parameter slot does not match its ordering expression");
      }
      @SuppressWarnings("unchecked")
      ParameterSlot<V> typedSlot = (ParameterSlot<V>) slot;
      return new ComparisonPredicate<>(expression, operator, typedSlot);
    }

    private static <V> SqlPredicate emptyMembership(SqlExpression<V> expression) {
      return new InPredicate<>(expression, List.of(), false);
    }
  }
}
